@echo off
set /p token=Nhap token (bo trong neu khong co): 

if "%token%"=="" (
    java -jar loto-client-3.0.0.jar --ws --port 8001 --name admin
) else (
    java -jar loto-client-3.0.0.jar --ws --port 8001 --name admin --token %token%
)

pause