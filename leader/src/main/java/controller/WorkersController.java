/*
 * Copyright 2019 Hopper
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package controller;

import cache.WorkersCache;
import com.google.gson.Gson;
import main.APP;
import model.ZooWorkerDataModel;
import org.apache.log4j.Logger;
import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;
import org.json.JSONObject;
import zookeeper.ZooController;
import zookeeper.ZooPathTree;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Random;


public class WorkersController extends ZooController {

    protected final static Logger LOG = Logger.getLogger(WorkersController.class);
    private final WorkersCache workersCache;
    private final String zooWorkersPath;
    private final String zooAssignmentPath;
    private final APP app;

    public WorkersController(APP app) {
        this.app = app;
        this.zooWorkersPath = ZooPathTree.WORKERS.concat("/").concat(app.getAppName());
        this.zooAssignmentPath = ZooPathTree.ASSIGN.concat("/").concat(app.getAppName());

        this.workersCache = new WorkersCache();
    }

    public void getWorkersFromZookeeper() {

        Watcher workersChangeWatcher = new Watcher() {
            @Override
            public void process(WatchedEvent watchedEvent) {
                if (watchedEvent.getType() == Event.EventType.NodeChildrenChanged) {

                    assert zooWorkersPath.equals(watchedEvent.getPath());

                    getWorkersFromZookeeper();

                }
            }
        };

        zk.getChildren(zooWorkersPath, workersChangeWatcher, new AsyncCallback.ChildrenCallback() {
            @Override
            public void processResult(int i, String s, Object o, List<String> workerList) {
                List<String> tempWorkerList = null;

                switch(KeeperException.Code.get(i)){

                    case CONNECTIONLOSS:

                        getWorkersFromZookeeper();
                        break;

                    case OK:

                        LOG.info(new StringBuilder().append("Suscessfully got a list of workers: ").append(workerList.size()).append(" workers"));
                        getWorkersAsStringList().forEach((e) -> LOG.info(e));

                        tempWorkerList = deletedWorerkList(workerList);

                        tempWorkerList.forEach((e) -> {
                            try {

                                deleteWorkerAssignode(e);

                                //TODO control this exceptions
                            } catch (KeeperException e1) {

                                e1.printStackTrace();
                            } catch (InterruptedException e1) {

                                e1.printStackTrace();
                            }
                        });

                        updateWorkerList(workerList);
                        getWorkerListAsString().forEach((e) -> LOG.info(new StringBuilder().append("   * ").append(e)));

                        //Get zookeeper tasks in orger to: No workers registered when there are new task to do manager
                        app.getAssignTaskController().getZookeeperTasks();

                        break;

                    default:
                        LOG.error(new StringBuilder("getChildren Failed ").append(KeeperException.create(KeeperException.Code.get(i), s)));
                }

            }
        }, null);

    }

    public List<String> getWorkersAsStringList() {

        return workersCache.getCachedWorkerListAsString();
    }

    /**
     * Delete remote worker with the format:
     *
     * @param worker
     * @throws KeeperException
     * @throws InterruptedException
     */
    public void deleteWorkerAssignode(String worker) throws KeeperException, InterruptedException {
        LOG.info("Deleting zookeeperworker assignment ".concat(worker));
        String workerPath = zooAssignmentPath.concat("/").concat(worker);

        zk.getChildren(workerPath, true, new AsyncCallback.ChildrenCallback() {
            @Override
            public void processResult(int i, String s, Object o, List<String> children) {
                TasksController tasksController = new TasksController();

                LOG.info("Starting task reassingment");
                String currentPath;
                switch (KeeperException.Code.get(i)){

                    case CONNECTIONLOSS:

                        //assignTaskController.getZookeeperTasks();
                        break;

                    case OK:

                        if(children != null){

                            for(String taskName : children){

                                currentPath = s.concat("/").concat(taskName);

                                LOG.info("moving assignment from ".concat(currentPath).concat( " to ").concat(ZooPathTree.TASKS.concat("/")
                                        .concat(app.getAppName()).concat("/").concat(taskName)));

                                try {
                                    tasksController.createTask(app.getAppName(),taskName, tasksController.syncGetNodedata(currentPath));
                                    app.getAssignTaskController().deleteAssignment(worker,taskName);

                                } catch (KeeperException e) {
                                    LOG.error("imposible to get task data from assignment");

                                } catch (InterruptedException e) {
                                    LOG.error("imposible to get task data from assignment");
                                    LOG.error(e);
                                }
                            }
                        }

                        deleteEmptyAssignemtNode(worker);

                        break;

                    default:

                        LOG.error(new StringBuilder().append("getChildren failed ").append(KeeperException.create(KeeperException.Code.get(i), s)));

                }
            }
        }, null);
    }

    public void deleteEmptyAssignemtNode(String worker) {
        LOG.info("Deleting zookeeperworker assignment ".concat(worker));
        String workerPath = zooAssignmentPath.concat("/").concat(worker);

        try {
            zk.delete(workerPath, -1);
        } catch (InterruptedException e) {
            LOG.error(e);
            e.printStackTrace();
        } catch (KeeperException e) {
            LOG.error(e);
            e.printStackTrace();
        }
    }

    public List<String> deletedWorerkList(List<String> workersList) {
        return workersCache.deletedWorkerList(workersList);
    }

    public List<String> getWorkerListAsString() {
        return workersCache.getCachedWorkerListAsString();
    }

    public void updateWorkerList(List<String> workerList) {

        List<String> workerTemp = null;
        workerTemp = workersCache.deletedWorkerList(workerList);

        if (workerTemp.size() != 0) {

            workerTemp.forEach((e) -> workersCache.deleteWorker(e));
        }

        workerTemp = workersCache.addedWorkerList(workerList);

        if (workerTemp.size() != 0) {

            workerTemp.forEach((e) -> {

                zk.getData(zooWorkersPath.concat("/").concat(e), null, new AsyncCallback.DataCallback() {

                    @Override
                    public void processResult(int i, String path, Object o, byte[] data, Stat stat) {

                        //TODO control states
                        switch (KeeperException.Code.get(i)) {

                            case CONNECTIONLOSS:

                                zk.getData(zooWorkersPath.concat("/").concat(e), null, this, null);
                                break;

                            case OK:

                                workersCache.addNewChildren(
                                        new Gson().fromJson(
                                                new String(data, StandardCharsets.UTF_8),ZooWorkerDataModel.class));

                                break;

                            default:

                                LOG.error(new StringBuilder().append("getChildren failed ").append(KeeperException.create(KeeperException.Code.get(i), path)));

                        }
                    }
                }, null);

            });
        }
    }

    //TODO CONTROL NO UP WORKERS
    public synchronized ZooWorkerDataModel bookIdleWorker() {

        List<ZooWorkerDataModel> idleWorkerList = this.workersCache.getCachedIddleWorkerLIstAsString();

        LOG.info(new StringBuilder().append("Eligiendo entre ").append(idleWorkerList.size()).append(" workers"));

        int worker = new Random().nextInt(idleWorkerList.size());

        ZooWorkerDataModel designatedWorker = idleWorkerList.get(worker);
        //TODO do it atomicly
        designatedWorker.setNumAsignedTasks(designatedWorker.getNumAsignedTasks() + 1);

        return designatedWorker;

    }

    //TODO control exceptions
    public synchronized void doneAssignmentTask(ZooWorkerDataModel workerCacheModel) {

        String workerPath = zooWorkersPath.concat("/").concat(workerCacheModel.getWorkerId());


        try {
            //TODO do it asyn in order to control different that cache
            zk.setData(workerPath, new Gson().toJson(workerCacheModel).getBytes(), -1);

        } catch (KeeperException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public Optional<ZooWorkerDataModel> getWorkerDatabyID(String workerId){

       return workersCache.getCachedWorkerList().stream()
               .filter(workerCacheModel -> workerCacheModel.getWorkerId().equals(workerId))
               .findFirst();
    }
}
