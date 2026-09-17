# Выпуск Листай в SourceCraft

Актуальная процедура находится в [проектном навыке `listai-release`](../.agents/skills/listai-release/SKILL.md).
Он описывает проверки перед выпуском, сборку и проверку подписи постоянным ключом,
push, CI, тег и публикацию APK. Секреты и новый ключ в репозитории не хранятся.

Репозиторий: [avstaim/listai](https://sourcecraft.dev/avstaim/listai), remote
`origin`, основная ветка `main`. Версия берётся из `gradle.properties`; RC2 —
`1.0.0-rc2`, `versionCode = 12`, тег `v1.0.0-rc2`.

## Ссылки, закреплённые на тегах

- [README RC2](https://sourcecraft.dev/avstaim/listai/browse/README.md?rev=tag%3Av1.0.0-rc2)
- [Release notes 1.0.0-rc2](https://sourcecraft.dev/avstaim/listai/browse/docs/releases/1.0.0-rc2.md?rev=tag%3Av1.0.0-rc2)
- [Приёмка RC2](https://sourcecraft.dev/avstaim/listai/browse/docs/rc2-task.md?rev=tag%3Av1.0.0-rc2)
- [Исторический RC1](https://sourcecraft.dev/avstaim/listai/releases/v1.0.0-rc1)
- [Исторические notes 0.9](https://sourcecraft.dev/avstaim/listai/browse/docs/releases/0.9.md?rev=tag%3Av0.9)

APK и контрольная сумма публикуются на странице
[SourceCraft releases/v1.0.0-rc2](https://sourcecraft.dev/avstaim/listai/releases/v1.0.0-rc2).
Фактические локальные проверки и SHA подписанного файла фиксируются в
[`rc2-task.md`](rc2-task.md). Физические устройства в область RC2 не входят.
