#!/usr/bin/env python3
"""
Android Emulator Launcher Script
启动 Android 虚拟机
"""

import os
import subprocess
import sys
import argparse


def get_sdk_path():
    """获取 Android SDK 路径"""
    # 从环境变量获取
    sdk_path = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    
    if sdk_path and os.path.exists(sdk_path):
        return sdk_path
    
    # Windows 默认路径
    default_paths = [
        os.path.expanduser(r"~\AppData\Local\Android\Sdk"),
        r"C:\Android\Sdk",
    ]
    
    for path in default_paths:
        if os.path.exists(path):
            return path
    
    return None


def list_avds(emulator_path):
    """列出所有可用的 Android 虚拟机"""
    try:
        result = subprocess.run(
            [emulator_path, "-list-avds"],
            capture_output=True,
            text=True,
            check=True
        )
        avds = [avd.strip() for avd in result.stdout.strip().split('\n') if avd.strip()]
        return avds
    except subprocess.CalledProcessError as e:
        print(f"错误：无法获取虚拟机列表 - {e.stderr}")
        return []


def start_emulator(emulator_path, avd_name, no_window=False, cold_boot=False):
    """启动 Android 模拟器"""
    cmd = [emulator_path, "-avd", avd_name]
    
    if no_window:
        cmd.append("-no-window")
    
    if cold_boot:
        cmd.append("-no-snapshot")
    
    print(f"正在启动模拟器：{avd_name}")
    print(f"命令：{' '.join(cmd)}")
    
    # 使用 Popen 让模拟器在后台运行
    process = subprocess.Popen(cmd)
    
    return process


def main():
    parser = argparse.ArgumentParser(
        description="启动 Android 虚拟机",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  python start_avd.py                      # 使用默认或唯一的 AVD 启动
  python start_avd.py -l                   # 列出所有可用的 AVD
  python start_avd.py -a Pixel_API_34      # 启动指定的 AVD
  python start_avd.py --cold-boot          # 冷启动（不使用快照）
        """
    )
    
    parser.add_argument(
        "-l", "--list",
        action="store_true",
        help="列出所有可用的 Android 虚拟机"
    )
    
    parser.add_argument(
        "-a", "--avd",
        type=str,
        help="要启动的虚拟机名称"
    )
    
    parser.add_argument(
        "-s", "--sdk",
        type=str,
        help="Android SDK 路径（默认从环境变量或默认路径获取）"
    )
    
    parser.add_argument(
        "--no-window",
        action="store_true",
        help="无窗口模式（仅用于 CI/CD）"
    )
    
    parser.add_argument(
        "--cold-boot",
        action="store_true",
        help="冷启动，不使用快照"
    )
    
    args = parser.parse_args()
    
    # 确定 SDK 路径
    sdk_path = args.sdk or get_sdk_path()
    
    if not sdk_path:
        print("错误：未找到 Android SDK 路径")
        print("请设置 ANDROID_HOME 环境变量或使用 -s 参数指定 SDK 路径")
        sys.exit(1)
    
    print(f"Android SDK 路径：{sdk_path}")
    
    # 确定模拟器可执行文件路径
    if sys.platform == "win32":
        emulator_path = os.path.join(sdk_path, "emulator", "emulator.exe")
    else:
        emulator_path = os.path.join(sdk_path, "emulator", "emulator")
    
    if not os.path.exists(emulator_path):
        print(f"错误：未找到模拟器可执行文件：{emulator_path}")
        sys.exit(1)
    
    # 列出可用的 AVD
    avds = list_avds(emulator_path)
    
    if not avds:
        print("错误：未找到任何可用的 Android 虚拟机")
        print("请使用 Android Studio 的 Device Manager 创建一个虚拟机")
        sys.exit(1)
    
    # 列出模式
    if args.list:
        print("\n可用的 Android 虚拟机:")
        for avd in avds:
            print(f"  - {avd}")
        return
    
    # 确定要启动的 AVD
    if args.avd:
        avd_name = args.avd
        if avd_name not in avds:
            print(f"错误：未找到虚拟机 '{avd_name}'")
            print(f"可用的虚拟机：{', '.join(avds)}")
            sys.exit(1)
    else:
        # 默认使用第一个 AVD
        avd_name = avds[0]
        print(f"未指定虚拟机，使用默认：{avd_name}")
    
    # 启动模拟器
    process = start_emulator(
        emulator_path,
        avd_name,
        no_window=args.no_window,
        cold_boot=args.cold_boot
    )
    
    print(f"\n模拟器正在启动... (进程 ID: {process.pid})")
    print("提示：模拟器首次启动可能需要几分钟")


if __name__ == "__main__":
    main()
