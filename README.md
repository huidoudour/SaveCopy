# SaveCopy

### 个人修改版
本仓库是基于 [RikkaApps/SaveCopy](https://github.com/RikkaApps/SaveCopy) 的个人修改版本，
以上游 SaveCopy v2.0.0（提交 `4dd12a8`）为基线。

主要改动包括：

- 添加操作选择对话框与标准保存副本入口
- 添加下载副本功能
- 支持自定义保存目录

本项目（包括本仓库中的修改）以 **GNU GPL v3.0** 发布。完整许可证见 [LICENSE](LICENSE)，
上游来源、基线版本及修改声明见 [NOTICE](NOTICE)。发布 APK 时，应同时提供与该 APK 对应版本的完整、可构建源代码。

[![AI Assisted](https://img.shields.io/badge/AI-Assisted-blue.svg)](https://github.com/RikkaApps/SaveCopy)


## Background

One of the biggest changes of Android 11 is that all apps targeting 30 can only access its' private folder. Google Play will force new/updated apps to upgrade their target API one year later, so apps must make changes.

However, the problem is, some app does not do this correctly. For example, some chat apps, save **files users received from other users** to their **private folder** (`Android/data`) and does not provide options to copy/move these files. In Android 11, no one except the app itself can access those files, so users have to open those apps every time. This is very inconvenient and unreasonable. At least those apps allow the user to open files with other apps. So we have a chance.

This app does a very simple thing, handle `ACTION_VIEW`, and save the file to the `Download` folder.
