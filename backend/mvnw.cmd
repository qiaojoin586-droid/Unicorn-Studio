@REM ----------------------------------------------------------------------------
@REM Licensed to the Apache Software Foundation (ASF) under one
@REM or more contributor license agreements.  See the NOTICE file
@REM distributed with this work for additional information
@REM regarding copyright ownership.  The ASF licenses this file
@REM to you under the Apache License, Version 2.0 (the
@REM "License"); you may not use this file except in compliance
@REM with the License.  You may obtain a copy of the License at
@REM
@REM    https://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing,
@REM software distributed under the License is distributed on an
@REM "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
@REM KIND, either express or implied.  See the License for the
@REM specific language governing permissions and limitations
@REM under the License.
@REM ----------------------------------------------------------------------------

@REM Begin all REM://
@echo off
@REM Set title of console window
title "%~dp0\mvnw"

@REM Enable extensions
@setlocal

set ERROR_CODE=0

@REM To isolate internal variables from possible post scripts, we use another setlocal
@setlocal

@REM ==== START VALIDATION ====
if NOT "%JAVA_HOME%"=="" goto OkJHome
for %%i in (java.exe) do set "JAVACMD=%%~$PATH:i"
goto checkJCmd

:OkJHome
set "JAVACMD=%JAVA_HOME%\bin\java.exe"

:checkJCmd
if exist "%JAVACMD%" goto chkMHome

echo The JAVA_HOME environment variable is not defined correctly, >&2
echo this environment variable is needed to run this program. >&2
goto error

:chkMHome
if not "%MAVEN_PROJECTBASEDIR%"=="" goto baseDirFound
set "MAVEN_PROJECTBASEDIR=%~dp0"
goto getBaseDirPath

:baseDirFound
set "MAVEN_PROJECTBASEDIR=%~dp0"

:getBaseDirPath
for %%i in ("%MAVEN_PROJECTBASEDIR%\.") do set "MAVEN_PROJECTBASEDIR=%%~fi"

@REM Find the project basedir, i.e. the directory that contains the pom.xml file
set "EXEC_DIR=%CD%"
set "WDIR=%EXEC_DIR%"

@REM Look for Maven distribution
if exist "%MAVEN_PROJECTBASEDIR%\mvnw.cmd" goto baseDirFound2
if exist "%MAVEN_PROJECTBASEDIR%\pom.xml" goto baseDirFound2
echo Error: Could not find Maven project base directory. >&2
goto error

:baseDirFound2

@REM Extension to allow automatically downloading the maven-wrapper.jar from Maven-central
@REM This allows using the maven wrapper in projects that prohibit checking in binary data.
set WRAPPER_JAR="%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.jar"

if exist %WRAPPER_JAR% goto runMvnw

echo Downloading Maven wrapper...
@REM Use PowerShell to download
powershell -Command "&{"^
    "$webclient = new-object System.Net.WebClient;"^
    "$webclient.DownloadFile('https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar', '%WRAPPER_JAR%')"^
    "}"

if exist %WRAPPER_JAR% goto runMvnw
echo Failed to download Maven wrapper JAR. >&2
goto error

:runMvnw

@REM Provide a "standardized" way to retrieve the CLI args as will
@REM temporary work around for Maven's argument parsing
set MAVEN_CMD_LINE_ARGS=%*

@REM Find the project basedir
set MAVEN_OPTS="-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%"

%JAVACMD% ^
  %MAVEN_OPTS% ^
  %MAVEN_DEBUG_OPTS% ^
  -classpath %WRAPPER_JAR% ^
  "-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%" ^
  org.apache.maven.wrapper.MavenWrapperMain %MAVEN_CMD_LINE_ARGS%

if ERRORLEVEL 1 goto error
goto end

:error
set ERROR_CODE=1

:end
@endlocal & set ERROR_CODE=%ERROR_CODE%

cmd /C exit /B %ERROR_CODE%
