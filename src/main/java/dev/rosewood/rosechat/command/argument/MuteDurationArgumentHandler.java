package dev.rosewood.rosechat.command.argument;

import dev.rosewood.rosechat.RoseChat;
import dev.rosewood.rosechat.command.argument.MuteDuration.Unit;
import dev.rosewood.rosechat.manager.LocaleManager;
import dev.rosewood.rosegarden.command.framework.Argument;
import dev.rosewood.rosegarden.command.framework.ArgumentHandler;
import dev.rosewood.rosegarden.command.framework.CommandContext;
import dev.rosewood.rosegarden.command.framework.InputIterator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MuteDurationArgumentHandler extends ArgumentHandler<MuteDuration> {

    private final Map<String, Unit> localisedTimescales;

    public MuteDurationArgumentHandler() {
        super(MuteDuration.class);
        LocaleManager localeManager = RoseChat.getInstance().getManager(LocaleManager.class);
        this.localisedTimescales = new HashMap<>();
        this.add(localeManager, Unit.SECOND);
        this.add(localeManager, Unit.MINUTE);
        this.add(localeManager, Unit.HOUR);
        this.add(localeManager, Unit.DAY);
        this.add(localeManager, Unit.MONTH);
        this.add(localeManager, Unit.YEAR);
    }

    @Override
    public MuteDuration handle(CommandContext context, Argument argument, InputIterator inputIterator)
            throws HandledArgumentException {
        String amountInput = inputIterator.next();
        String timescaleInput = inputIterator.next();
        if (timescaleInput.isEmpty())
            throw new HandledArgumentException("command-mute-scale-required");

        int amount;
        try {
            amount = Integer.parseInt(amountInput);
        } catch (NumberFormatException exception) {
            throw new HandledArgumentException("argument-handler-integer");
        }
        Unit unit = this.localisedTimescales.get(timescaleInput);
        if (unit == null)
            throw new HandledArgumentException("argument-handler-timescale");
        if (amount < 1)
            throw new HandledArgumentException("argument-handler-integer");
        return new MuteDuration(amount, unit);
    }

    @Override
    public List<String> suggest(CommandContext context, Argument argument, String[] args) {
        return args.length <= 1 ? List.of("<time>") : List.copyOf(this.localisedTimescales.keySet());
    }

    private void add(LocaleManager localeManager, Unit unit) {
        this.localisedTimescales.put(localeManager.getMessage("command-mute-" + unit.name().toLowerCase()), unit);
        this.localisedTimescales.put(localeManager.getMessage("command-mute-" + unit.name().toLowerCase() + "s"), unit);
    }
}
