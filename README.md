# Описание задачи

Необходимо реализовать на языке Java (можно использовать 11 версию) класс для работы с API Честного знака. Класс должен быть thread-safe и поддерживать ограничение на количество запросов к API. Ограничение указывается в конструкторе в виде количества запросов в определенный интервал времени. Например:
public CrptApi(TimeUnit timeUnit, int requestLimit)
timeUnit – указывает промежуток времени – секунда, минута и пр.
requestLimit – положительное значение, которое определяет максимальное количество запросов в этом промежутке времени.
При превышении лимита запрос должен блокироваться, чтобы не превысить максимальное количество запросов к API и продолжить выполнение, когда ограничение не превышено.

Реализовать нужно единственный метод – Создание документа для ввода в оборот товара, произведенного в РФ. Документ и подпись должны передаваться в метод в виде Java объекта и строки соответственно.
При реализации можно использовать библиотеки HTTP клиента, JSON сериализации. Реализация должна быть максимально удобной для последующего расширения функционала.

Решение должно быть оформлено в виде одного файла CrptApi.java. Все дополнительные классы, которые используются должны быть внутренними.

Документация по работе с API вы найдете во вложении.

# Результат

Для полноценной проверки работы запросов мне не хватило данных, например: адрес сервера для отправки запроса, УКЭП (выдается при регистрации, а ссылка на регистрацию из документации не валидна). Да и в целом задание кажется очень абстрактным. В письме к заданию указано, что ответы по тестовой работе не предусмотрены. Поэтому надеюсь, что понял его правильно.

Поскольку нет четкого указания, на всякий случай в своей реализации выполняю проверку его полей перед отправкой (*в том числе с использованием Stream API и регулярного выражения*). Хотя по условию допустимы только библиотеки HTTP клиента и JSON сериализатора, все же использовал еще и Lombok для облегчения читаемости кода при проверке. Также не очень понял, обязательны ли поля из расширения производителей молока, если запрос по производителю другой продукции. Не стал их учитывать.

**Чтобы проверить соответствие лимиту перед отправкой запроса выбрана следующая реализация:**
- В конструкторе класса получаем значение лимита сообщений, единицу измерения времени и количество этих единиц.
- Вычисляем и сохраняем этот интервал времени в миллисекундах.
- С помощью интерфейса Lock и его реализации с "честной" блокировкой изменяем значение счетчика запросов.
- При соблюдении условия счетчик увеличивается на единицу и выполняется запрос. В отдельном потоке запускается обратный отсчет заданного временного интервала.
- Если условие не соблюдается, то поток подписывается на изменения в критической секции и останавливается в ожидании.
- Каждый запущенный поток с обратным отсчетом завершается уменьшением счетчика на единицу и уведомлением подписанных потоков об изменении значения счетчика.
- Таким образом счетчик увеличивается при каждой отправке запроса и уменьшается по завершении обратного отсчета. А за счет Lock изменения являются потокобезопасными.