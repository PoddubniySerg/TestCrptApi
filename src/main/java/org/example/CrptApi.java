package org.example;

import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.chrono.ChronoLocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

public class CrptApi {

    private static final String DATE_FORMAT_PATTERN = "yyyy-MM-dd";
    private static final String URL = "https://<host:port>/api/v2/{extension}/rollout";
    private static final String QUERY_OMS_ID_PARAM = "omsId=<Unique_OMS_identifier>";
    private static final String CONTENT_TYPE_HEADER_NAME = "Content-Type";
    private static final String CONTENT_TYPE_VALUE = "application/json";
    private static final String CLIENT_TOKEN_HEADER_NAME = "clientToken";
    private static final String USERNAME_HEADER_NAME = "userName";
    private static final String USERNAME_VALUE = "user_name";
    private static final String ACCEPT_HEADER_NAME = "Accept";
    private static final String ACCEPT_VALUE = "*/*";

    private final long delay;
    private final int requestLimit;
    private final Validator validator = new Validator();
    private final Lock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();
    private final ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    private int counter;

    public CrptApi(TimeUnit timeUnit, int amount, int requestLimit) {
        this.requestLimit = Math.max(requestLimit, 0);
        this.delay = amount < 0 ? 0 : timeUnit.toMillis(amount);
    }

    public Model.IntroduceResponse introduceProduct(Model.Document document, String clientToken) throws Exceptions.NullTokenException, Exceptions.SendDocumentException, Exceptions.InvalidDocumentException {
//        Если документ не валиден или токен равен null, выбрасываем соответствующее исключение
        if (!this.validator.isValid(document))
            throw new Exceptions.InvalidDocumentException("Invalid value of the document");
        if (clientToken == null) throw new Exceptions.NullTokenException("Token can not be null");
        try {
//        Увеличиваем счетчик запросов в соответствии с условиями и выполняем запрос.
//        Возвращаем полученный объект из ответа сервера.
            increase();
            return send(document, clientToken);
        } catch (Exception e) {
            throw new Exceptions.SendDocumentException("The document didn't send: " + document);
        }
    }

    private void increase() throws InterruptedException {
//        Если счетчик уже превышает допустимый лимит запросов, то подписываемся на изменение его значения.
//        Если значение счетчика позволяет выполнить запрос, увеличиваем его на единицу, открываем критическую секцию.
        try {
            this.lock.lock();
            while (this.counter > this.requestLimit) this.condition.await();
            ++this.counter;
        } finally {
            this.lock.unlock();
        }
    }

    private Model.IntroduceResponse send(Model.Document document, String clientToken) throws IOException, InterruptedException {
//        Начинаем с запуска потока с обратным отсчетом заданного временного интервала
        executor.execute(this::startTimer);
//        Предполагаю, что запуск основного метода уже выполнен в отдельном потоке,
//        поэтому запрос на сервер выполняем синхронно с блокировкой нашего потока до получения ответа
        final var client = HttpClient.newHttpClient();
//        Устанавливаем шаблон формата даты при сериализации в соответствии с описанием в API
        final var gson = new GsonBuilder().setDateFormat(DATE_FORMAT_PATTERN).create();
//        В примере запроса из описания API указаны только заголовки Accept и clientToken,
//        однако, согласно документации, могут быть и Content-Type и userName.
        final var request = HttpRequest.newBuilder()
                .uri(URI.create(URL + "?" + QUERY_OMS_ID_PARAM))
                .header(CONTENT_TYPE_HEADER_NAME, CONTENT_TYPE_VALUE)
                .header(CLIENT_TOKEN_HEADER_NAME, clientToken)
                .header(USERNAME_HEADER_NAME, USERNAME_VALUE)
                .header(ACCEPT_HEADER_NAME, ACCEPT_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(document)))
                .build();
//        Выполняем запрос, а результат объект ответа сохраняем в переменную
        final var response = client.send(request, HttpResponse.BodyHandlers.ofString());
//        Десериализуем тело ответа в объект нужного класса и возвращаем его
        return gson.fromJson(response.body(), Model.IntroduceResponse.class);
    }

    private void startTimer() {
        try {
//          Останавливаем поток на заданное время
            Thread.sleep(this.delay);
//            Уменьшаем счетчик на единицу
            --this.counter;
//            Сообщаем всем подписчикам об изменении в критической секции
            this.condition.signalAll();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}

/**
 * Классы для описания модели документа и ответа сервера
 */
class Model {

    @Getter
    @Setter
    @AllArgsConstructor
    static
    class IntroduceResponse {
        @SerializedName("omsId")
        private String omsId;
        @SerializedName("reportId")
        private String reportId;
    }

    /**
     * @param produced nullable
     * @param anImport nullable
     */
    @Builder
    record Document(
            @SerializedName("usageType")
            String usageType,
            @SerializedName("documentFormat")
            String documentFormat,
            @SerializedName("type")
            String type,
            @SerializedName("participantInn")
            String participantInn,
            @SerializedName("productionDate")
            LocalDate productionDate,
            @SerializedName("products")
            List<Product> products,
            @SerializedName("produced")
            Produced produced,
            @SerializedName("import")
            Import anImport
    ) {
    }

    /**
     * @param certificateDocument       nullable
     * @param certificateDocumentDate   nullable
     * @param certificateDocumentNumber nullable
     */
    @Builder
    record Product(
            @SerializedName("code")
            String code,
            @SerializedName("certificateDocument")
            String certificateDocument,
            @SerializedName("certificateDocumentDate")
            LocalDate certificateDocumentDate,
            @SerializedName("certificateDocumentNumber")
            String certificateDocumentNumber,
            @SerializedName("tnvedCode")
            String tnvedCode
    ) {
    }

    @Builder
    record Produced(
            @SerializedName("producerInn")
            String producerInn,
            @SerializedName("ownerInn")
            String ownerInn,
            @SerializedName("productionType")
            String productionType
    ) {
    }

    @Builder
    record Import(
            @SerializedName("declarationDate")
            LocalDate declarationDate,
            @SerializedName("declarationNumber")
            String declarationNumber,
            @SerializedName("customsCode")
            String customsCode,
            @SerializedName("decisionCode")
            long decisionCode
    ) {
    }

}

/**
 * Перечисления допустимых значений в документе, согласно описанию в API
 */
class Enums {

    enum UsageType {
        SENT_TO_PRINTER("SENT_TO_PRINTER");

        private final String value;

        UsageType(String value) {
            this.value = value;
        }

        String getValue() {
            return this.value;
        }
    }

    enum DocumentFormat {
        MANUAL("MANUAL");

        private final String value;

        DocumentFormat(String value) {
            this.value = value;
        }

        String getValue() {
            return this.value;
        }
    }

    enum Type {
        LP_INTRODUCE_GOODS_AUTO("LP_INTRODUCE_GOODS_AUTO"),
        LP_GOODS_IMPORT_AUTO("LP_GOODS_IMPORT_AUTO");

        private final String value;

        Type(String value) {
            this.value = value;
        }

        String getValue() {
            return this.value;
        }
    }

    enum CertificateDocument {
        CERTIFICATE("1"),
        DECLARATION("2");

        private final String code;

        CertificateDocument(String code) {
            this.code = code;
        }

        String getCode() {
            return this.code;
        }
    }

    enum ProductionType {
        OWN_PRODUCTION("OWN_PRODUCTION");

        private final String value;

        ProductionType(String value) {
            this.value = value;
        }

        String getValue() {
            return this.value;
        }
    }

    enum InnLength {
        TEN(10),
        TWELVE(12);

        private final int amount;

        InnLength(int amount) {
            this.amount = amount;
        }

        int getAmount() {
            return this.amount;
        }
    }

}

/**
 * Класс для валидации полученного в основном методе документа
 */
class Validator {

    private static final int MAX_YEARS_FOR_CERTIFICATE_DOCUMENT = 5;
    private static final int TNVED_CODE_LENGTH = 10;
    private static final Pattern REGEX = Pattern.compile("^[0-9]+$");

    boolean isValid(Model.Document document) {
        return document.usageType() != null
                && Arrays.stream(Enums.UsageType.values()).anyMatch(usageType -> usageType.getValue().equals(document.usageType()))
                && document.documentFormat() != null
                && Arrays.stream(Enums.DocumentFormat.values()).anyMatch(documentFormat -> documentFormat.getValue().equals(document.documentFormat()))
                && document.type() != null
                && Arrays.stream(Enums.Type.values()).anyMatch(type -> type.getValue().equals(document.type()))
                && document.participantInn() != null
                && innIsValid(document.participantInn())
                && isValid(document.productionDate())
                && isValid(document.products())
                && isValid(document.produced(), document.type())
                && isValid(document.anImport(), document.type())
                ;
    }

    private boolean isValid(List<Model.Product> products) {
        return products.stream()
                .noneMatch(product ->
                        product.tnvedCode() == null
                                || product.tnvedCode().length() != TNVED_CODE_LENGTH
                                || product.tnvedCode().isBlank()
                                || !certificateDocumentDateIsValid(product.certificateDocumentDate())
                                || !certificateDocumentIsValid(product.certificateDocument())
                                || product.code() == null
                                || product.code().isEmpty()
                                || product.code().isBlank()
                );
    }

    private boolean isValid(Model.Import importObj, String type) {
        if (type.equals(Enums.Type.LP_INTRODUCE_GOODS_AUTO.getValue()) && importObj == null) return true;
        return type.equals(Enums.Type.LP_GOODS_IMPORT_AUTO.getValue())
                && importObj.decisionCode() > 0
                && importObj.customsCode() != null
                && !importObj.customsCode().isBlank()
                && importObj.declarationNumber() != null
                && !importObj.declarationNumber().isBlank()
                && importObj.declarationDate() != null
                && isValid(importObj.declarationDate());
    }

    private boolean isValid(Model.Produced produced, String type) {
        if (type.equals(Enums.Type.LP_GOODS_IMPORT_AUTO.getValue()) && produced == null) return true;
        return type.equals(Enums.Type.LP_INTRODUCE_GOODS_AUTO.getValue())
                && produced.productionType() != null
                && produced.productionType().equals(Enums.ProductionType.OWN_PRODUCTION.getValue())
                && innIsValid(produced.ownerInn())
                && innIsValid(produced.producerInn());
    }

    private boolean isValid(LocalDate date) {
        final var nowDate = LocalDate.now();
        return date != null
                && date.isAfter(ChronoLocalDate.from(nowDate.minusYears(MAX_YEARS_FOR_CERTIFICATE_DOCUMENT)))
                && date.isBefore(nowDate);
    }

    private boolean innIsValid(String inn) {
        return inn != null
                && (inn.length() == Enums.InnLength.TEN.getAmount() || inn.length() == Enums.InnLength.TWELVE.getAmount())
                && !inn.isBlank()
                && REGEX.matcher(inn).matches();
    }

    private boolean certificateDocumentDateIsValid(LocalDate date) {
        if (date == null) return true;
        return isValid(date);
    }

    private boolean certificateDocumentIsValid(String certificateDocument) {
        if (certificateDocument == null) return true;
        return
                certificateDocument.equals(Enums.CertificateDocument.CERTIFICATE.getCode())
                        || certificateDocument.equals(Enums.CertificateDocument.DECLARATION.getCode());
    }
}

/**
 * Классы исключений
 */
class Exceptions {

    static class InvalidDocumentException extends Exception {
        public InvalidDocumentException(String message) {
            super(message);
        }
    }

    static class NullTokenException extends Exception {

        public NullTokenException(String message) {
            super(message);
        }
    }

    static class SendDocumentException extends Exception {

        public SendDocumentException(String message) {
            super(message);
        }
    }
}