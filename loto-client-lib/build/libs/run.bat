@echo off

for /f %%a in ('powershell -command "(Get-NetRoute -DestinationPrefix 0.0.0.0/0 | Select -First 1).NextHop"') do set ROUTER_IP=%%a

echo Router IP: %ROUTER_IP%

set /p NAME=Nhap name: 

java -jar loto-client-2.0.0.jar --ws %ROUTER_IP%:8001 --name %NAME%

pause