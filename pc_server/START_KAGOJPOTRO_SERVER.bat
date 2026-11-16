@echo off
title Kagojpotro 4TB Office PC Server
echo ============================================================================
echo   STARTING KAGOJPOTRO OFFICE PC SERVER (Port 8085)
echo   Change "D:\Kagojpotro_Storage" below to your desired 4TB drive folder!
echo ============================================================================
python "%~dp0kagojpotro_pc_server.py" --storage "D:\Kagojpotro_Storage" --port 8085
pause
