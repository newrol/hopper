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

import API.TaskAPIController;
import main.Client;
import main.ClientStates;
import org.apache.log4j.Logger;
import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;
import org.json.JSONObject;
import zookeeper.ZooController;
import zookeeper.ZooPathTree;

import java.util.List;

import static org.apache.zookeeper.ZooDefs.Ids.OPEN_ACL_UNSAFE;

public class ZooTaskController extends ZooController {

    private final static Logger LOG = Logger.getLogger(ZooTaskController.class);
    private ClientStates state;


    public ZooTaskController(){

    }

    public void getTasks(){

        LOG.info("Client-".concat(Client.SERVER_ID).concat(" getting tasks"));

        Watcher tasksChangeWatcher;

        tasksChangeWatcher = new Watcher() { public void process(WatchedEvent e) {
            if(e.getType() == Event.EventType.NodeChildrenChanged) { assert ZooPathTree.ASSIGN.concat(Client.SERVER_ID).equals( e.getPath() );
                getTasks();
            }
        }};

        LOG.info(ZooPathTree.TASK_MODEL);
        zk.getChildren(ZooPathTree.TASK_MODEL, tasksChangeWatcher, new AsyncCallback.ChildrenCallback() {
            @Override
            public void processResult(int rc, String path, Object ctx, List<String> children) {

                switch (KeeperException.Code.get(rc)) {

                    case CONNECTIONLOSS:

                        getTasks();
                        break;

                    case OK:
                        //TODO control task are not created yet
                        LOG.info(new StringBuilder().append("Suscessfully got new Task: ").append(children.get(0)));
                        geTaskData(children.get(0));
                        break;

                    default:

                        LOG.error(new StringBuilder("getChildren Failed ").append(KeeperException.create(KeeperException.Code.get(rc), path)));
                }
            }
        }, null);
    }

    public void geTaskData(String taskName){

        //TODO make it async

        zk.getData(ZooPathTree.TASK_MODEL.concat("/").concat(taskName), false, new AsyncCallback.DataCallback() {
            @Override
            public void processResult(int i, String s, Object o, byte[] bytes, Stat stat) {

                switch(KeeperException.Code.get(i)) {

                    case CONNECTIONLOSS:

                        geTaskData(taskName);
                        break;

                    case OK:

                        String taskData = new String(bytes);

                        LOG.info("Starting task: ".concat((String) o));


                        LOG.info("Task info: ".concat(stat.toString()));
                        LOG.info("Getting task Body");

                        LOG.info("TaskData: ".concat(taskData));

                        TaskAPIController.updateAPI(new JSONObject(taskData));
                        break;

                    default:
                        LOG.error(new StringBuilder("TaskDataCallback failed ").append(KeeperException.create(KeeperException.Code.get(i), s)));

                }
            }
        },taskName);
    }

    public void submitNewTask(String taskID, JSONObject jsonObject) {

        zk.create(ZooPathTree.TASKS.concat("/").concat(taskID),
                jsonObject.toString().getBytes(), OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, new AsyncCallback.StringCallback() {
                    @Override
                    public void processResult(int rc, String path, Object ctx, String name) {

                        switch (KeeperException.Code.get(rc)) {

                            case CONNECTIONLOSS:

                                submitNewTask(taskID, jsonObject);
                                break;

                            case OK:

                                LOG.info("task was created correctly".concat(": ").concat(path));
                                break;

                            default:
                                LOG.error("Something went wrong" +  KeeperException.create(KeeperException.Code.get(rc), path));
                        }
                    }
        }, null);
    }

    public void submitDeleteTask(String taskIDConter) {

        zk.create(ZooPathTree.TASK_DELETE.concat("/").concat(taskIDConter),
                "".getBytes(), OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, new AsyncCallback.StringCallback() {
                    @Override
                    public void processResult(int rc, String path, Object ctx, String name) {

                        switch (KeeperException.Code.get(rc)) {

                            case CONNECTIONLOSS:

                                submitDeleteTask(taskIDConter);
                                break;

                            case OK:

                                LOG.info("task was queued to delete correctly".concat(": ").concat(path));
                                break;

                            default:
                                LOG.error("Something went wrong" +  KeeperException.create(KeeperException.Code.get(rc), path));
                        }
                    }
        }, null);
    }
}
