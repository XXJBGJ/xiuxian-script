@echo off
chcp 65001 >nul
cd /d "%~dp0"
start "修仙脚本工具后端" python server.py
timeout /t 2 /nobreak >nul
start "" http://127.0.0.1:8765
