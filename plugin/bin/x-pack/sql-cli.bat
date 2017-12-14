@echo off

rem Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
rem or more contributor license agreements. Licensed under the Elastic License;
rem you may not use this file except in compliance with the Elastic License.

setlocal enabledelayedexpansion
setlocal enableextensions

call "%~dp0..\elasticsearch-env.bat" || exit /b 1

call "%~dp0x-pack-env.bat" || exit /b 1

set CLI_JAR=!ES_CLASSPATH!;!ES_HOME!/plugins/x-pack/bin/sql-cli-*.jar

%JAVA% ^
  -jar "%CLI_JAR%" ^
  %*

endlocal
endlocal
