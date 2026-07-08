#!/bin/bash

env=$1;

if [ "a$env" == "a" ]; then
    echo "Please pass environment value as param. possible values are 'test', 'preprod', 'prod'";
    exit 1;
fi

if [ "$env" != "test" ] && [ "$env" != "preprod" ] && [ "$env" != "prod" ]; then
    echo "Only possible values for the environment are 'test' or 'preprod' or 'prod'"
    exit 1;
fi

echo "Going to stop the healing house application for >>$env<< environment";

pid=$(ps -elf|grep "healinghouse.jar --spring.profiles.active=$env"|grep -v grep|awk '{print $4}')

echo "PID : >>$pid<<"

if [ "a$pid" == "a" ]; then
  echo "Healing house application is not running for >>$env<< environment"
else
  echo "Healing house application is running. PID >>$pid<<"
  ps -elf|grep "healinghouse.jar --spring.profiles.active=$env"|grep -v grep;
  echo "Going to kill the process";
  kill -9 $pid;
  echo "waiting for 5 secs ...";
  sleep 5;
  ps -elf|grep $pid|grep -v grep;
  pid_count=$(ps -elf|grep $pid|grep -v grep|wc -l);

  if [ "$pid_count" == "1" ]; then
    echo "Some issue killing the PID >>$pid<<. Check manually";
  else
    echo "Process >>$pid<< killed !!!!!";
  fi

fi

echo "DONE";