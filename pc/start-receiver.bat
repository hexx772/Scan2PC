@echo off
rem Starts the Scan2PC receiver window (no console window)
start "" powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0receiver.ps1"
