#!/bin/bash                                                

# Copyright (c) YugaByte, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
# in compliance with the License.  You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
# or implied.  See the License for the specific language governing permissions and limitations
# under the License.
#

#file: .jdbc_example_app_checker are just used for signaling the java app whether to continue or pause at this point of time
#file: .notify_shell_script is used to signal shell script from the java whether to continue or pause at this point of time

echoSleep() {
  echo "$1"
  SLEEP 1
}

#this function will check and print the verbose statement if required
verbosePrint() {
  if [ $1 -eq 1 ]
  then
    echo "$2"
  fi
}

#this function will be called at the end of the script for cleaning
finish() {
  echoSleep "End of example, destroying the created cluster...."
  $1/bin/yb-ctl destroy  >> yb-ctl.log 2>&1

  # running the remaining java app
  touch .jdbc_example_app_checker3

  ##kill the java app if exists
  kill -9 $2 >> yb-ctl.log 2>&1

  #deleting the temporary files
  rm -rf .jdbc_example_app_checker  
  rm -rf .jdbc_example_app_checker2
  rm -rf .jdbc_example_app_checker3 
  rm -rf .notify_shell_script
}

#this function basically checks the $file content and keep it paused until required content is present
pauseScript() {
  #just creating the file if it doesn't exsits
  file=.notify_shell_script
  touch $file

  # echo "script paused"
  while [[ $(cat $file) != $1 ]]
  do
    dummy_var=1
  done
  # echo "script continued"
}

#this function pause the script for input from user, so that user can easily view the previous commands output
interact() {
  if [ $1 -eq 1 ]
  then
    read -p "Press ENTER to continue" dummy
    SLEEP 0.2
  fi
}

VERBOSE=$1
INTERACTIVE=$2
DEBUG=$3
INSTALL_DIR=$4

verbosePrint $VERBOSE "Destroying any exsiting cluster if present..."
$INSTALL_DIR/bin/yb-ctl destroy  > yb-ctl.log 2>&1

echo "Creating a 3-node, RF-3 cluster (live nodes: 1,2,3)"
$INSTALL_DIR/bin/yb-ctl create --rf 3  >> yb-ctl.log 2>&1


#deleting the checker file if exists
verbosePrint $VERBOSE "Deleting all the temporary checker files if exists"
rm -rf .jdbc_example_app_checker  
rm -rf .jdbc_example_app_checker2
rm -rf .jdbc_example_app_checker3 #to keep the java app running until killed
rm -rf .notify_shell_script

classpath=target/jdbc-yugabytedb-example-0.0.1-SNAPSHOT.jar
#Starting the Uniform Load Balance Example app
java -cp $classpath com.yugabyte.examples.UniformLoadBalanceExample $VERBOSE $INTERACTIVE $DEBUG 2>&1  &
# java -cp $classpath com.yugabyte.examples.UniformLoadBalance $VERBOSE $INTERACTIVE > jdbc-yugabytedb-example.log 2>&1  &


#storing the pid of the java app
jdbc_example_app_pid=$!

echoSleep "Java Example App has started running in background...."

pauseScript "add_node"

interact $INTERACTIVE

echoSleep "Adding Node-4 to the cluster (live nodes: 1,2,3,4)"
$INSTALL_DIR/bin/yb-ctl add_node >> yb-ctl.log 2>&1

touch .jdbc_example_app_checker   #resuming the java app

pauseScript "stop_node"

interact $INTERACTIVE

echoSleep "Stopping Node-3 in the cluster (live nodes: 1,2,4)"
$INSTALL_DIR/bin/yb-ctl stop_node 3 >> yb-ctl.log 2>&1

touch .jdbc_example_app_checker2  #resuming the java app

pauseScript "perform_cleanup"
SLEEP 2

interact $INTERACTIVE

finish $INSTALL_DIR $jdbc_example_app_pid

