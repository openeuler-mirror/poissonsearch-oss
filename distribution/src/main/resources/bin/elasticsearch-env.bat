set SCRIPT=%0

rem determine Elasticsearch home; to do this, we strip from the path until we
rem find bin, and then strip bin (there is an assumption here that there is no
rem nested directory under bin also named bin)
for %%I in (%SCRIPT%) do set ES_HOME=%%~dpI

:es_home_loop
for %%I in ("%ES_HOME:~1,-1%") do set DIRNAME=%%~nxI
if not "%DIRNAME%" == "bin" (
  for %%I in ("%ES_HOME%..") do set ES_HOME=%%~dpfI
  goto es_home_loop
)
for %%I in ("%ES_HOME%..") do set ES_HOME=%%~dpfI

rem now set the classpath
set ES_CLASSPATH=!ES_HOME!\lib\*

rem now set the path to java
if defined JAVA_HOME (
  set JAVA="%JAVA_HOME%\bin\java.exe"
) else (
  for %%I in (java.exe) do set JAVA="%%~$PATH:I"
)

if not exist %JAVA% (
  echo could not find java; set JAVA_HOME or ensure java is in PATH 1>&2
  exit /B 1
)

rem JAVA_OPTS is not a built-in JVM mechanism but some people think it is so we
rem warn them that we are not observing the value of %JAVA_OPTS%
if not "%JAVA_OPTS%" == "" (
  echo|set /p="warning: ignoring JAVA_OPTS=%JAVA_OPTS%; "
  echo pass JVM parameters via ES_JAVA_OPTS
)

rem check the Java version
%JAVA% -cp "%ES_CLASSPATH%" "org.elasticsearch.tools.JavaVersionChecker"

if errorlevel 1 (
  echo|set /p="the minimum required Java version is 8; "
  echo your Java version from %JAVA% does not meet this requirement
  exit /B 1
)

if "%CONF_DIR%" == "" (
  set CONF_DIR=!ES_HOME!\config
)
