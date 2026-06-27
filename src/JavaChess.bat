@echo off
rem UCI launcher — add THIS file as a UCI engine in Arena / CuteChess.
rem Requires Java 17+ on PATH and the compiled .class files in this folder
rem (run:  javac *.java   once, in this folder).
cd /d "%~dp0"
java UCI
