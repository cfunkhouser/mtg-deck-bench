package mtgbench.deck;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import forge.card.CardRules;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckRecognizer;
import forge.deck.DeckRecognizer.Token;
import forge.deck.DeckRecognizer.TokenType;
import forge.deck.DeckSection;
import forge.deck.io.DeckSerializer;
import forge.item.PaperCard;
import forge.util.FileSection;
import mtgbench.config.Format;

/**
 * Reads deck files and checks they are usable before any game is played.
 *
 * <ul>
 *   <li>{@code .dck}: Forge's own format, read by Forge.
 *   <li>Anything else: a plain-text list ({@code 4 Lightning Bolt}, {@code 4x ...},
 *       {@code 4 Lightning Bolt (M11) 149}, section headers such as {@code Sideboard}, and MTGO
 *       exports where a blank line starts the sideboard), read line by line with Forge's
 *       {@link DeckRecognizer} so that problems can name the line.
 * </ul>
 *
 * <p>Forge itself never rejects a card it doesn't know: it substitutes a placeholder card and plays
 * on. So every card is checked here, and all problems are reported together.
 */
public final class DeckLoader {
    /** Printing details after the card name: "(M11) 149", "[M11]", "|M11", "|M11|1". */
    private static final Pattern PRINTING_SUFFIX = Pattern.compile(
            "\\s*(\\([A-Za-z0-9]{2,6}\\)(\\s+\\S+)?|\\[[A-Za-z0-9]{2,6}\\]|\\|.*)\\s*$");

    /** "*CMDR*" marks the commander line in MTGO-style exports from deck sites. */
    private static final Pattern CMDR_MARKER = Pattern.compile("\\s*\\*CMDR\\*\\s*", Pattern.CASE_INSENSITIVE);

    /** A trailing tag group: "[Ramp]", "[Commander{top}]", "^Have,#37d67a^" (Archidekt exports). */
    private static final Pattern TRAILING_TAG = Pattern.compile("\\s*(\\[([^\\]]*)\\]|\\^[^^]*\\^)\\s*$");
    private static final Pattern SET_CODE = Pattern.compile("[A-Za-z0-9]{2,6}");
    private static final Pattern HAS_PRINTING = Pattern.compile("\\([A-Za-z0-9]{2,6}\\)");

    private static final Pattern SECTION_HEADER = Pattern.compile(
            "^\\s*(//\\s*)?(deck|main|maindeck|main deck|sideboard|commander|commanders|companion)\\s*:?\\s*$",
            Pattern.CASE_INSENSITIVE);

    private final Format format;
    private final boolean checkLegality;

    public DeckLoader(Format format, boolean checkLegality) {
        this.format = format;
        this.checkLegality = checkLegality;
    }

    public LoadedDeck load(Path file) throws DeckException {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new DeckException(file, List.of("cannot read file: " + e.getMessage()));
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        List<String> lines = text.lines().toList();
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String defaultName = file.getFileName().toString().replaceFirst("\\.[^.]+$", "");

        Deck deck = isForgeDeckFile(file)
                ? readForgeDeck(lines, defaultName, problems)
                : readTextList(lines, defaultName, format, problems, warnings);

        if (deck != null) {
            validate(deck, problems, warnings);
        }
        if (!problems.isEmpty()) {
            throw new DeckException(file, problems);
        }
        return new LoadedDeck(deck.getName(), file, sha256(bytes), deck, List.copyOf(warnings));
    }

    private static boolean isForgeDeckFile(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".dck");
    }

    private static Deck readForgeDeck(List<String> lines, String defaultName, List<String> problems) {
        Map<String, List<String>> sections = FileSection.parseSections(lines);
        Deck deck = DeckSerializer.fromSections(sections);
        if (deck == null) {
            problems.add("not a valid Forge .dck file (missing [metadata] or [main] section?)");
            return null;
        }
        if (deck.getName() == null || deck.getName().isBlank()) {
            deck.setName(defaultName);
        }
        return deck;
    }

    private static Deck readTextList(List<String> lines, String defaultName, Format format,
                                     List<String> problems, List<String> warnings) {
        DeckRecognizer recognizer = new DeckRecognizer();
        Deck deck = new Deck(defaultName);
        DeckSection section = DeckSection.Main;
        boolean hasHeaders = lines.stream().anyMatch(l -> SECTION_HEADER.matcher(l).matches());
        boolean seenCard = false;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            int lineNo = i + 1;
            if (line.isEmpty()) {
                // "Commander" header, its card(s), a blank line, then the deck without a "Deck" header.
                if (section == DeckSection.Commander && deck.has(DeckSection.Commander)
                        && !deck.get(DeckSection.Commander).isEmpty()) {
                    section = DeckSection.Main;
                    continue;
                }
                // MTGO/plain exports without headers: the first blank line after cards starts the sideboard.
                // Not for Commander, which has no sideboard and whose exports use blank lines freely.
                if (format != Format.COMMANDER && !hasHeaders && seenCard && section == DeckSection.Main) {
                    section = DeckSection.Sideboard;
                }
                continue;
            }
            if (line.startsWith("#")) {
                continue;
            }
            if (line.regionMatches(true, 0, "SB:", 0, 3)) {
                // MTGO "SB: 2 Card Name"
                Token t = recognizeWithFallback(recognizer, line.substring(3).strip(), DeckSection.Sideboard,
                        lineNo, warnings);
                seenCard |= addToken(deck, t, DeckSection.Sideboard, true, lineNo, line, problems, warnings);
                continue;
            }
            String original = line;
            boolean markedCommander = false;
            java.util.regex.Matcher cmdr = CMDR_MARKER.matcher(line);
            if (cmdr.find()) {
                markedCommander = true;
                line = cmdr.replaceAll(" ").strip();
            }
            // Strip deck-site tag groups. A bracket right after the name could be a set code ("[M11]"),
            // so it is only treated as a tag if the line also has a "(SET)" printing or it can't be a set code.
            java.util.regex.Matcher tag;
            while ((tag = TRAILING_TAG.matcher(line)).find()) {
                String inner = tag.group(2);
                String before = line.substring(0, tag.start());
                if (inner != null && !HAS_PRINTING.matcher(before).find() && SET_CODE.matcher(inner).matches()) {
                    break;
                }
                if (inner != null && inner.toLowerCase(Locale.ROOT).startsWith("commander")) {
                    markedCommander = true;
                }
                line = before.strip();
            }
            Token t = recognizeWithFallback(recognizer, line, markedCommander ? DeckSection.Commander : section,
                    lineNo, warnings);
            if (t == null) {
                continue;
            }
            if (markedCommander) {
                seenCard |= addToken(deck, t, DeckSection.Commander, true, lineNo, original, problems, warnings);
                continue;
            }
            if (t.getType() == TokenType.DECK_SECTION_NAME) {
                section = DeckSection.valueOf(t.getText());
                continue;
            }
            if (t.getType() == TokenType.DECK_NAME) {
                deck.setName(t.getText());
                continue;
            }
            seenCard |= addToken(deck, t, section, false, lineNo, original, problems, warnings);
        }
        return deck;
    }

    /**
     * Recognizes a line. If it names a printing Forge doesn't have (an unknown set code or collector
     * number, common with exports from newer sets), retries with the name alone and warns, rather
     * than rejecting a deck whose cards Forge does know.
     */
    private static Token recognizeWithFallback(DeckRecognizer recognizer, String line, DeckSection section,
                                               int lineNo, List<String> warnings) {
        Token t = recognizer.recognizeLine(line, section);
        if (t == null || t.getType() != TokenType.UNKNOWN_CARD) {
            return t;
        }
        String nameOnly = PRINTING_SUFFIX.matcher(line).replaceFirst("");
        if (nameOnly.equals(line)) {
            return t;
        }
        Token retry = recognizer.recognizeLine(nameOnly, section);
        if (retry != null && retry.isCardToken() && retry.getCard() != null) {
            warnings.add("line " + lineNo + " (\"" + line + "\"): printing not found, using "
                    + retry.getCard().getEdition() + " instead");
            return retry;
        }
        return t;
    }

    /** @return true if the token added a card. */
    /** @param forceSection put the card in {@code section} even if the recognizer chose another */
    private static boolean addToken(Deck deck, Token t, DeckSection section, boolean forceSection, int lineNo,
                                    String line, List<String> problems, List<String> warnings) {
        if (t == null) {
            return false;
        }
        String where = "line " + lineNo + " (\"" + line + "\")";
        switch (t.getType()) {
            case UNKNOWN_CARD -> problems.add(where + ": unknown card");
            case UNSUPPORTED_CARD -> problems.add(where + ": card is not implemented in this Forge version");
            case UNSUPPORTED_DECK_SECTION -> problems.add(where + ": unsupported deck section");
            case UNKNOWN_TEXT -> problems.add(where + ": cannot parse");
            case WARNING_MESSAGE -> warnings.add(where + ": " + t.getText());
            case COMMENT, CARD_TYPE, CARD_RARITY, CARD_CMC, MANA_COLOUR -> { /* informational lines */ }
            default -> {
                if (t.isCardToken() && t.getCard() != null) {
                    DeckSection target = forceSection || t.getTokenSection() == null ? section : t.getTokenSection();
                    deck.getOrCreate(target).add(t.getCard(), t.getQuantity());
                    if (t.getType() == TokenType.CARD_FROM_INVALID_SET) {
                        warnings.add(where + ": unknown set code, using another printing");
                    }
                    return true;
                }
                problems.add(where + ": not understood (" + t.getType() + ")");
            }
        }
        return false;
    }

    private void validate(Deck deck, List<String> problems, List<String> warnings) {
        if (deck.getMain().isEmpty()) {
            problems.add("main deck is empty");
        }
        if (format == Format.COMMANDER && deck.getCommanders().isEmpty()) {
            problems.add("no commander: put it under a 'Commander' header, mark its line with *CMDR*, "
                    + "or use a [Commander] section in a .dck file");
        }
        List<String> aiBad = new ArrayList<>();
        for (Map.Entry<DeckSection, CardPool> part : deck) {
            for (Map.Entry<PaperCard, Integer> e : part.getValue()) {
                CardRules rules = e.getKey().getRules();
                String name = e.getKey().getName();
                if (rules == null || rules.isUnsupported()) {
                    problems.add(part.getKey() + ": unknown or unsupported card \"" + name + "\"");
                } else if (part.getKey() != DeckSection.Sideboard && rules.getAiHints().getRemAIDecks()
                        && !aiBad.contains(name)) {
                    aiBad.add(name);
                }
            }
        }
        if (!aiBad.isEmpty()) {
            warnings.add("Forge's AI is known to play these cards poorly (AI:RemoveDeck), so results for this "
                    + "deck are less reliable: " + String.join(", ", aiBad));
        }
        if (checkLegality && problems.isEmpty()) {
            String fmt = format.name().toLowerCase(Locale.ROOT);
            String problem = format.deckFormat().getDeckConformanceProblem(deck);
            if (problem != null) {
                problems.add("not legal for " + fmt + ": " + problem + " (use --no-legality-check to run it anyway)");
            }
            // getDeckConformanceProblem doesn't apply the format's per-card filter, which is where
            // Forge keeps e.g. the Commander banned list, so check every card here as well.
            List<String> banned = new ArrayList<>();
            for (Map.Entry<DeckSection, CardPool> part : deck) {
                for (Map.Entry<PaperCard, Integer> e : part.getValue()) {
                    if (!format.deckFormat().isLegalCard(e.getKey()) && !banned.contains(e.getKey().getName())) {
                        banned.add(e.getKey().getName());
                    }
                }
            }
            if (!banned.isEmpty()) {
                problems.add("banned or not allowed in " + fmt + ": " + String.join(", ", banned)
                        + " (use --no-legality-check to run it anyway)");
            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
