# 一键启动开发服务脚本
# 使用方法: 右键 -> 使用 PowerShell 运行

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  一键启动开发服务脚本" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# 1. 清理 MCP 缓存（解决 context7 连接问题）
Write-Host "[1/3] 清理 MCP 缓存..." -ForegroundColor Yellow
$npxCache = "$env:LOCALAPPDATA\npm-cache\_npx"
if (Test-Path $npxCache) {
    Remove-Item -Recurse -Force $npxCache -ErrorAction SilentlyContinue
    Write-Host "      npx 缓存已清理" -ForegroundColor Green
} else {
    Write-Host "      缓存目录不存在，跳过" -ForegroundColor Gray
}
Write-Host ""

# 2. 启动 Android 模拟器（可选）
Write-Host "[2/3] 检查 Android 模拟器..." -ForegroundColor Yellow
$emulatorPath = "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe"
if (Test-Path $emulatorPath) {
    # 获取可用的 AVD 列表
    $avds = & $emulatorPath -list-avds 2>$null
    if ($avds) {
        Write-Host "      可用模拟器: $avds" -ForegroundColor Gray
        Start-Process -FilePath $emulatorPath -ArgumentList "-avd", $avds[0] -WindowStyle Hidden
        Write-Host "      模拟器启动中..." -ForegroundColor Green
    }
} else {
    Write-Host "      未找到 Android 模拟器" -ForegroundColor Gray
}
Write-Host ""

# 3. 启动 Appium 服务器
Write-Host "[3/3] 启动 Appium 服务器..." -ForegroundColor Yellow
$appium = Get-Command appium -ErrorAction SilentlyContinue
if ($appium) {
    Start-Process -FilePath "cmd" -ArgumentList "/k", "appium --base-path /wd/hub"
    Write-Host "      Appium 已启动 (端口 4723)" -ForegroundColor Green
} else {
    Write-Host "      Appium 未安装，跳过" -ForegroundColor Gray
}
Write-Host ""

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  启动完成！" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "提示：MCP 服务器 (context7) 由 Claude Code 自动管理" -ForegroundColor DarkGray
Write-Host "按任意键退出..." -ForegroundColor DarkGray
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
