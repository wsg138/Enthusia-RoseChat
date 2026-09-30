package dev.rosewood.rosechat.message.tokenizer.composer.decorator.adventure;

import dev.rosewood.rosechat.message.tokenizer.Token;
import dev.rosewood.rosechat.message.tokenizer.decorator.ClickDecorator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;

public class AdventureClickDecorator extends AdventureTokenDecorator<ClickDecorator> {

    public AdventureClickDecorator(ClickDecorator decorator) {
        super(decorator);
    }

    @Override
    public Component apply(Component component, Token parent) {
        String value = parent.getPlaceholders().apply(this.decorator.value());
        if (this.decorator.action() == ClickDecorator.Action.OPEN_URL && !ClickDecorator.PATTERN.matcher(value).find())
            value = "https://" + value;

        ClickEvent clickEvent = switch (this.decorator.action()) {
            case OPEN_URL -> ClickEvent.openUrl(value);
            case OPEN_FILE -> ClickEvent.openFile(value);
            case RUN_COMMAND -> ClickEvent.runCommand(value);
            case SUGGEST_COMMAND -> ClickEvent.suggestCommand(value);
            case CHANGE_PAGE -> ClickEvent.changePage(Integer.parseInt(value));
            case COPY_TO_CLIPBOARD -> ClickEvent.copyToClipboard(value);
        };

        return component.clickEvent(clickEvent);
    }

}
