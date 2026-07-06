#!/bin/bash

env=$1;

if [ "a$env" == "a" ]; then
    echo "Please pass environment value as param. possible values are 'test', 'prod'";
    exit 1;
fi

echo "Going to stop the healing house application for >>$env<< environment";

pid=$(pgrep -f healinghouse.jar --spring.profiles.active=$env)

if [ "a$pid" == "a" ]; then
  echo "Healing house application is not running for >>$env<< environment"
else
  pgrep -fa healinghouse.jar --spring.profiles.active=$env;
  echo "Going to kill the process";
  kill -9 $pid;
  ps -elf|grep $pid;
  echo "Process >>$pid<< killed";
fi

echo "DONE";