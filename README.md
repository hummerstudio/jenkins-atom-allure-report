# jenkins-atom-allure

把 `allure-results` 变成报告，不需要 Allure CLI。

Allure 插件会去调 Allure 命令行，但真正有用的那半——**数字与失败用例**——只是 JSON。这个原子读 `*-result.json`、统计 passed/failed/broken/skipped、算耗时，写一个自包含 HTML，并以 `report` 出参交给构建页内嵌。

不装 Node、不装 Allure、不联网；`failOnFailure=true` 时有失败用例就让这一步红。

```bash
./build.sh && ./tools/smoke.sh
```
