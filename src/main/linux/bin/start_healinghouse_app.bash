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

echo "Going to start the healing house application for >>$env<< environment";

export APP_INIT_JAVA_HEAP=512M
export APP_MAX_JAVA_HEAP=1024M
app_home="/home/healinghouse/apps/healinghouse"
logs_dir="$app_home/logs"
logback_config="$app_home/conf/logback-spring.xml";
hh_external_properties="$app_home/conf/hh_external.yml";

if [ "a$HEALING_HOUSE_DB_PASSWORD" == "a" ]; then
  echo "DB password : >>$HEALING_HOUSE_DB_PASSWORD<< is blank. Set env variable >>HEALING_HOUSE_DB_PASSWORD<< and then start again";
else

  #check if process is already running
  pid=$(ps -elf|grep healinghouse.jar|grep -i java|grep -v grep |awk '{print $4}');

  if [ "a$pid" == "a" ]; then
    nohup java -Xms$APP_INIT_JAVA_HEAP -Xmx$APP_MAX_JAVA_HEAP -DHEALING_HOUSE_DB_PASSWORD=$HEALING_HOUSE_DB_PASSWORD -Dhh_external_properties=$hh_external_properties -Dlogs_dir=$logs_dir -Dlogging.config=$logback_config -jar $app_home/lib/healinghouse.jar --spring.profiles.active=$env&
    PID=$!
    echo "Started healing house aplication with PID: $PID";
  else
    echo "Application is already running. Please stop it first";
  fi
fi