# Appium MCP 安装指南

## 安装

```bash
npm install -g @nbakka/mcp-appium
```

## 配置 Claude Code

```bash
claude mcp add appium -- npx @nbakka/mcp-appium
```

## 验证

```bash
echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | npx @nbakka/mcp-appium
```

## 前提条件

- Android: 开启 USB 调试，`adb devices` 能看到设备
- iOS: 需要配置 Xcode 和 WebDriverAgent

## 可用工具

- `mobile_list_available_devices` - 列出设备
- `mobile_use_device` - 选择设备
- `mobile_launch_app` - 启动应用
- `mobile_tap_by_text` - 点击元素
- `mobile_type_keys` - 输入文本
- `mobile_press_button` - 按键(BACK/HOME等)
- `swipe_on_screen` - 滑动

## 常见问题

如果官方包 `appium-mcp` 安装失败（sharp 依赖问题），使用 `@nbakka/mcp-appium` 替代。
