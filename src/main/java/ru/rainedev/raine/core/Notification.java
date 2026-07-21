package ru.rainedev.raine.core;

/**
 * Событие, требующее внимания: сообщение, напоминание, повод подумать.
 *
 * @param text  что Raine увидит
 * @param tools действия, доступные немедленно — например, открыть чат
 */
public record Notification(String text, Toolbox tools) {

    public Notification(String text) {
        this(text, new Toolbox());
    }
}
