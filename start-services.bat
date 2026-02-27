@echo off
chcp 65001 >nul
echo ========================================
echo   一键启动开发服务脚本
echo ========================================
echo.

:: 检查管理员权限（用于某些服务）
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo [提示] 部分服务可能需要管理员权限
    echo.
)

:: 1. 启动 Android 模拟器（可选）
echo [1/4] 检查 Android 模拟器...
set EMULATOR_PATH=%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe
if exist "%EMULATOR_PATH%" (
    echo       找到模拟器，正在后台启动...
    start "" /B "%EMULATOR_PATH%" -avd Medium_Phone_API_35 >nul 2>&1
    echo       模拟器启动中，请稍候...
) else (
    echo       未找到模拟器路径
)
echo.

:: 2. 启动 Appium 服务器
echo [2/4] 启动 Appium 服务器...
where appium >nul 2>&1
if %errorLevel% equ 0 (
    start "Appium Server" cmd /k "appium --base-path /wd/hub"
    echo       Appium 服务器已启动 (端口 4723)
) else (
    echo       Appium 未安装，跳过...
)
echo.

:: 3. 清理并预热 MCP 缓存
echo [3/4] 预热 MCP 服务器缓存...
echo       清理可能损坏的 npx 缓存...
if exist "%LOCALAPPDATA%\npm-cache\_npx" (
    rd /s /q "%LOCALAPPDATA%\npm-cache\_npx" 2>nul
)
echo       缓存已清理
echo.

:: 4. 显示服务状态
echo [4/4] 服务状态检查...
echo.
echo   已启动的服务:
echo   --------------------
tasklist /fi "imagename eq node.exe" 2>nul | find "node.exe" >nul
if %errorLevel% equ 0 (
    echo   [√] Node.js 进程运行中
) else (
    echo   [ ] Node.js 进程未运行
)

tasklist /fi "imagename eq qemu-system-x86_64.exe" 2>nul | find "qemu" >nul
if %errorLevel% equ 0 (
    echo   [√] Android 模拟器运行中
) else (
    echo   [ ] Android 模拟器未检测到
)

netstat -an | find "4723" | find "LISTENING" >nul
if %errorLevel% equ 0 (
    echo   [√] Appium 端口 4723 已监听
) else (
    echo   [ ] Appium 端口 4723 未监听
)
echo.

echo ========================================
echo   启动完成！
echo ========================================
echo.
echo   提示：
echo   - MCP 服务器 (context7) 由 Claude Code 自动管理
echo   - 如需重启 Claude Code，请关闭后重新打开
echo   - Appium 服务器在新窗口中运行
echo.
pause
