# Выпуск Листай в SourceCraft

Актуальная процедура находится в [проектном навыке `listai-release`](../.agents/skills/listai-release/SKILL.md).
Он описывает проверки перед выпуском, сборку и проверку подписи постоянным ключом,
push, CI, тег и публикацию APK. Секреты и новый ключ в репозитории не хранятся.

Репозиторий: [avstaim/listai](https://sourcecraft.dev/avstaim/listai), remote
`origin`, основная ветка `main`. Версия берётся из `gradle.properties`; текущий
кандидат — `1.0-rc1`, `versionCode = 13`, тег `v1.0-rc1`.

## Ссылки, закреплённые на тегах

- [README 1.0-rc1](https://sourcecraft.dev/avstaim/listai/browse/README.md?rev=tag%3Av1.0-rc1)
- [Release notes 1.0-rc1](https://sourcecraft.dev/avstaim/listai/browse/docs/releases/1.0-rc1.md?rev=tag%3Av1.0-rc1)
- [Исторические notes 0.9](https://sourcecraft.dev/avstaim/listai/browse/docs/releases/0.9.md?rev=tag%3Av0.9)

APK и контрольная сумма публикуются на странице
[SourceCraft releases/v1.0-rc1](https://sourcecraft.dev/avstaim/listai/releases/v1.0-rc1).
Фактические проверки перечислены в release notes и подтверждаются CI конкретного
release-коммита. Физические устройства в текущую область проверки не входят.
