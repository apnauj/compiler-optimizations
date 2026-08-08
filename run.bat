@echo off
REM Compiler Optimizations -- build ^& run (Windows)
cd /d "%~dp0"
if exist out rmdir /s /q out
mkdir out
dir /s /b src\*.java > sources.txt
javac -d out @sources.txt
del sources.txt
if "%1"=="" ( java -cp out optimizer.Main & goto :eof )
if "%1"=="all" (
  java -cp out demos.ConstantFoldingDemo
  java -cp out demos.DeadCodeEliminationDemo
  java -cp out demos.ConstantPropagationDemo
  java -cp out demos.PeepholeOptimizationDemo
  java -cp out demos.InlineExpansionDemo
  java -cp out optimizer.Main
  goto :eof
)
java -cp out %1
