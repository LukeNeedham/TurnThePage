# Встроенная повесть

«Седьмой огонь» — исходная русская повесть. Все переводы содержат полный текст:
три части, девять глав, эпилог, аннотацию, эпиграф, стихотворение, две сноски и
три иллюстрации. Римские номера в переведённых заголовках сохраняют раздельную
вёрстку номера главы и названия; части и эпилог не увеличивают число глав.

| Язык | Название | Ресурс в `app/src/main/res/raw/` |
| --- | --- | --- |
| Русский | Седьмой огонь | `stage4_reading_sample.fb2` |
| Английский | The Seventh Light | `stage4_reading_sample_en.fb2` |
| Немецкий | Das siebte Licht | `stage4_reading_sample_de.fb2` |
| Испанский | La séptima luz | `stage4_reading_sample_es.fb2` |
| Французский | La Septième Lumière | `stage4_reading_sample_fr.fb2` |
| Итальянский | La settima luce | `stage4_reading_sample_it.fb2` |
| Португальский (Бразилия) | A sétima luz | `stage4_reading_sample_pt_br.fb2` |

## Добавление в библиотеку

При первой инициализации выбирается только одна версия по первому языку системы.
`LocaleManagerCompat.getSystemLocales` исключает влияние отдельного языка
приложения на Android 13+. Региональные варианты поддержанных языков используют
соответствующий перевод; все португальские варианты используют бразильский.
Для неподдерживаемого или отсутствующего языка выбирается английская версия.

DataStore сохраняет `bundled_book_language` перед общим импортом FB2. Если процесс
прервётся после публикации файла/Room, повтор использует ту же версию и её SHA-256:
дедупликация оставляет прежние прогресс и закладки. После успешного импорта
`last_book` и `initialized` записываются одним изменением DataStore. Повторный
запуск, смена языка, обновление приложения и удаление повести не добавляют новую
версию. Старый `initialized=true` сохраняет библиотеку существующих установок.

## Обложки переводов

Шесть обложек локализованы встроенным `image_gen` по исходному `cover.jpg`,
встроены как JPEG `binary id="cover.jpg"` в соответствующие FB2. Исходная русская
обложка и три иллюстрации сохранены. Результаты осмотрены перед включением.
Применялся один и тот же набор инструкций, с отдельными точными подписями:

| Версия | Заголовок (две строки) | Автор |
| --- | --- | --- |
| en | THE SEVENTH / LIGHT | NINA LADOZHINA |
| de | DAS SIEBTE / LICHT | NINA LADOZHINA |
| es | LA SÉPTIMA / LUZ | NINA LADOZHINA |
| fr | LA SEPTIÈME / LUMIÈRE | NINA LADOJINA |
| it | LA SETTIMA / LUCE | NINA LADOŽINA |
| pt-BR | A SÉTIMA / LUZ | NINA LADOZHINA |

Промпт для каждой пары TITLE/AUTHOR из таблицы (`/` означает перевод строки):

> Use case: text-localization. Asset type: translated cover of an illustrated
> novella in an Android reader. Edit target is the attached original book cover.
> Replace ONLY all Russian/Cyrillic lettering: title at the top becomes exactly
> TITLE, author at bottom becomes exactly AUTHOR. Title is two lines as shown by
> newline, large cream classic serif capitals, centered; author smaller cream
> serif capitals centered at the bottom, both entirely within the frame. Preserve
> the complete 2:3 vertical cover, lighthouse, its beam, rocky coastline, waves,
> blue-grey cloudy sky, painterly texture, palette, all landscape details and the
> original type positioning. Make no other changes. No extra text, translated
> subtitle, Cyrillic remnants, watermark or logo. Make the accented letters exactly
> correct.

## Проверки

- JVM: выбор языка и fallback; полный разбор шести переводов, структура частей,
  номера глав, эпилог, ссылки сносок и декодирование четырёх JPEG каждого FB2.
- Android: резервирование языка после повторного открытия DataStore; прерывание
  импорта и повтор с другим языком без потери прогресса/закладок; сохранение
  удаления и старого флага инициализации; реальный per-app override на API 33+.
