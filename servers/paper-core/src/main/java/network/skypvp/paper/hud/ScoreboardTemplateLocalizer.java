package network.skypvp.paper.hud;

@FunctionalInterface
public interface ScoreboardTemplateLocalizer {

    ScoreboardTemplateLocalizer IDENTITY = (template, locale) -> template == null ? "" : template;

    String localize(String template, String locale);
}
