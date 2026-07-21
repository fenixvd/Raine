package ru.rainedev.raine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.rainedev.raine.llm.ToolCall;

class CurrentStepTest {

    private static ToolCall call(String name) {
        return new ToolCall("id-" + name, "function", new ToolCall.Function(name, "{}"));
    }

    @Test
    void toolSeesTheWholeStepAroundIt() {
        long[] seen = new long[1];
        Toolbox tools = new Toolbox(
                Tool.simple("send", "", arguments -> {
                    seen[0] = CurrentStep.countOf("send");
                    return "ok";
                }));

        tools.invoke(List.of(call("send"), call("send")));

        assertEquals(2, seen[0], "вызовов за шаг было два — инструмент должен это видеть");
    }

    @Test
    void nothingLeaksIntoTheNextStep() {
        Toolbox tools = new Toolbox(Tool.simple("send", "", arguments -> "ok"));
        tools.invoke(List.of(call("send"), call("send")));

        assertEquals(0, CurrentStep.countOf("send"), "шаг закончился — счёт обнуляется");
        assertEquals(List.of(), CurrentStep.calls());
    }
}
