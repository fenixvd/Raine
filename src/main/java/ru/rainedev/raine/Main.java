package ru.rainedev.raine;

import it.tdlight.Init;
import it.tdlight.Log;
import it.tdlight.Slf4JLogMessageHandler;
import it.tdlight.client.APIToken;
import it.tdlight.client.AuthenticationSupplier;
import it.tdlight.client.SimpleTelegramClientFactory;
import it.tdlight.client.TDLibSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.config.Config;

public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        Config config = Config.load();

        // проверить память можно и не поднимая Telegram: так видно ровно одно —
        // запрос и что на него ответил дневник
        if (args.length > 0 && args[0].equals("recall")) {
            ru.rainedev.raine.cli.RecallCommand.run(config, String.join(" ", java.util.Arrays.asList(args).subList(
                    1, args.length)));
            return;
        }

        Init.init();
        Log.setLogMessageHandler(1, new Slf4JLogMessageHandler());

        try (SimpleTelegramClientFactory factory = new SimpleTelegramClientFactory()) {
            TDLibSettings settings = TDLibSettings.create(new APIToken(config.apiId(), config.apiHash()));
            settings.setDatabaseDirectoryPath(config.sessionDir().resolve("data"));
            settings.setDownloadedFilesDirectoryPath(config.sessionDir().resolve("downloads"));

            // в списке активных сеансов Telegram видно устройство и приложение.
            // Значения по умолчанию выдают стороннего клиента с первого взгляда
            settings.setDeviceModel("Desktop");
            settings.setSystemVersion("Linux");
            settings.setApplicationVersion("1.0");
            settings.setSystemLanguageCode("ru");

            var builder = factory.builder(settings);
            var authentication = AuthenticationSupplier.user(config.phone());

            try (RaineApp app = new RaineApp(builder, authentication, config)) {
                app.start();
                app.client().waitForExit();
            }
        }
    }
}
