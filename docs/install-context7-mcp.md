# Context7 MCP 安装指南

## 安装命令

```bash
claude mcp add context7 -- npx -y @upstash/context7-mcp
```

## 常见问题：连接失败

症状：`SyntaxError: Unexpected end of input`

修复：
```bash
# Windows
rd /s /q "%LOCALAPPDATA%\npm-cache\_npx"
npm cache clean --force

# macOS/Linux
rm -rf ~/.npm/_npx
npm cache clean --force
```

修复后重启 Claude Code。
