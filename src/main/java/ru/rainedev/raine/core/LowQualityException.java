package ru.rainedev.raine.core;

/**
 * Не техническая ошибка, а сигнал «получилось плохо».
 * <p>
 * Цикл откатывается к состоянию до вызова и даёт модели попробовать иначе.
 * Технические сбои (нет сети, бан) бросаются обычными исключениями — их модель
 * видит текстом и может подстроиться.
 */
public class LowQualityException extends RuntimeException {

    public LowQualityException(String reason) {
        super(reason);
    }
}
