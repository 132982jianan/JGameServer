@echo off
setlocal EnableExtensions
chcp 65001 >nul

set "SERVER_DIR=%~dp0"
set "COMPOSE_FILE=%SERVER_DIR%deploy\docker-compose.yml"
set "ENV_FILE=%SERVER_DIR%deploy\.env"
set "NO_PAUSE="
set "PUSHED="
set "EXIT_CODE=1"

if /I "%~1"=="--no-pause" set "NO_PAUSE=1"

if not exist "%SERVER_DIR%pom.xml" goto server_not_found
if not exist "%COMPOSE_FILE%" goto compose_file_not_found
if not exist "%ENV_FILE%" goto env_file_not_found

pushd "%SERVER_DIR%"
if errorlevel 1 goto server_not_found
set "PUSHED=1"

echo [1/6] Building server jars with Maven...
call mvn -pl game-common,game-db,game-gm-server,game-logic-server,game-gateway-server,game-battle-server,game-chat-server -am clean package -DskipTests -q
if errorlevel 1 goto build_failed

echo [2/6] Checking Docker Desktop...
where docker >nul 2>&1
if errorlevel 1 goto docker_not_found

docker compose version >nul 2>&1
if errorlevel 1 goto compose_not_found

docker info >nul 2>&1
if errorlevel 1 goto docker_not_running

echo [3/6] Pulling Docker images...
rem --policy missing skips images that are already local. Without it Compose does a registry
rem freshness check on every image, so an unreachable Docker Hub fails the whole startup even
rem when nothing actually needs downloading.
docker compose --env-file "%ENV_FILE%" -f "%COMPOSE_FILE%" pull --policy missing
if errorlevel 1 call :verify_local_images
if errorlevel 1 goto compose_pull_failed

echo [4/6] Stopping existing Docker Compose services...
docker compose --env-file "%ENV_FILE%" -f "%COMPOSE_FILE%" down --remove-orphans
if errorlevel 1 goto compose_down_failed

echo [5/6] Starting Docker Compose services...
docker compose --env-file "%ENV_FILE%" -f "%COMPOSE_FILE%" up -d
if errorlevel 1 goto compose_up_failed

echo [6/6] Current service status:
docker compose --env-file "%ENV_FILE%" -f "%COMPOSE_FILE%" ps
if errorlevel 1 goto status_failed

echo.
echo JGameServer deployment completed successfully.
echo GM HTTP endpoint:      http://127.0.0.1:8080/gateway
echo Client TCP entry:      127.0.0.1:10001
echo Mongo:                 127.0.0.1:27017 (db: jgame_server)
echo Redis:                 127.0.0.1:6379
set "EXIT_CODE=0"
goto finish

:docker_not_found
echo ERROR: Docker CLI was not found. Install or repair Docker Desktop first.
goto finish

:compose_not_found
echo ERROR: The Docker Compose plugin is unavailable.
goto finish

:docker_not_running
echo ERROR: Docker Desktop is not running or the current user cannot access it.
goto finish

:server_not_found
echo ERROR: Server project was not found at "%SERVER_DIR%".
goto finish

:compose_file_not_found
echo ERROR: Compose file was not found at "%COMPOSE_FILE%".
goto finish

:env_file_not_found
echo ERROR: Compose env file was not found at "%ENV_FILE%".
goto finish

:build_failed
echo ERROR: Maven build failed. Docker Compose was not started.
goto finish

:compose_pull_failed
echo ERROR: Docker Compose failed to pull required images.
echo.
echo The failure is usually Docker Hub connectivity or Docker Desktop proxy configuration.
echo You can either configure Docker Desktop HTTPS proxy, pre-pull images manually, or edit deploy\.env:
echo   MONGO_IMAGE=your-mirror/mongo:4.4
echo   REDIS_IMAGE=your-mirror/redis:6.2-alpine
echo   NODE_IMAGE=your-mirror/eclipse-temurin:8-jre
goto finish

:compose_down_failed
echo ERROR: Docker Compose failed to stop the existing services. Startup was aborted.
goto finish

:compose_up_failed
echo ERROR: Docker Compose failed to start the services.
echo.
echo Docker Compose service status:
docker compose --env-file "%ENV_FILE%" -f "%COMPOSE_FILE%" ps -a
echo.
echo Mongo and Redis logs:
docker compose --env-file "%ENV_FILE%" -f "%COMPOSE_FILE%" logs --tail=120 mongo redis
goto finish

:status_failed
echo ERROR: Services started, but their status could not be read.

:finish
if defined PUSHED popd
echo.
if not defined NO_PAUSE pause
endlocal & exit /b %EXIT_CODE%

rem ---------------------------------------------------------------------------
rem Subroutines. Placed after the exit above so control never falls into them.
rem ---------------------------------------------------------------------------

rem Fallback when `pull --policy missing` still failed. If every required image is already
rem local there is nothing to download, so an unreachable registry should not block startup.
rem Returns 0 when all images are present, 1 otherwise.
:verify_local_images
echo WARNING: image pull failed. Checking whether every required image is already local...
set "MISSING_IMAGE="
set "CHECKED_IMAGES=0"
for /f "usebackq delims=" %%I in (`docker compose --env-file "%ENV_FILE%" -f "%COMPOSE_FILE%" config --images`) do call :check_one_image "%%I"
if "%CHECKED_IMAGES%"=="0" (
  echo ERROR: could not read the image list from the compose file.
  exit /b 1
)
if defined MISSING_IMAGE exit /b 1
echo All required images are present locally. Continuing without pulling.
exit /b 0

rem Sets MISSING_IMAGE when %1 is absent locally. Variables set here are visible to the
rem caller because `call` does not open a new setlocal scope, which keeps this loop free of
rem delayed-expansion pitfalls.
:check_one_image
set /a CHECKED_IMAGES+=1
docker image inspect %1 >nul 2>&1
if errorlevel 1 (
  echo   missing locally: %~1
  set "MISSING_IMAGE=1"
)
exit /b 0
