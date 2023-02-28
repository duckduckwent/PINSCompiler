/**
 * @Author: turk
 * @Description: Leksikalni analizator.
 */

package compiler.lexer;

import static common.RequireNonNull.requireNonNull;
import static compiler.lexer.TokenType.*;
import compiler.lexer.Position.Location;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import common.Report;

public class Lexer {
    /**
     * Izvorna koda.
     */
    private final String source;

    /**
     * Preslikava iz ključnih besed v vrste simbolov.
     */
    private final static Map<String, TokenType> keywordMapping;
    private enum possibleTokenType { OP, ID, INT, STR, COM, WHT, ERR }

    static {
        keywordMapping = new HashMap<>();
        keywordMapping.put("true", C_LOGICAL);
        keywordMapping.put("false", C_LOGICAL);
        for (var token : TokenType.values()) {
            var str = token.toString();
            if (str.startsWith("KW_")) {
                keywordMapping.put(str.substring("KW_".length()).toLowerCase(), token);
            }
            if (str.startsWith("AT_")) {
                keywordMapping.put(str.substring("AT_".length()).toLowerCase(), token);
            }
        }
    }

    /**
     * Na podlagi izbranega znaka preslika pripadajočo abstraktno TokenType vrednost (ID je lahko tudi KW ali AT).
     *
     * @param c Podan znak.
     * @return Vrne "mozen" TokenType glede na znak.
     */
    private static possibleTokenType getPossibleTokenType(char c) {
        c = Character.toLowerCase(c);
        if (97 <= c && c <= 122 || c == '_')
            return possibleTokenType.ID;

        if (Character.isDigit(c))
            return possibleTokenType.INT;

        return switch (c) {
            case '+', '-', '*', '/', '%', '&', '|', '!', '=', '<', '>', '(', ')', '[', ']', '{', '}', ':', ';', '.', ',' ->
                    possibleTokenType.OP;
            case ' ', '\t', '\r', '\n' -> possibleTokenType.WHT; // Tudi '\r' (newline na Windows shranjen kot '\r\n')
            case '\'' -> possibleTokenType.STR;
            case '#' -> possibleTokenType.COM;
            default -> possibleTokenType.ERR;
        };
    }

    /**
     * Preslikaj operator v njegovo ustrezno TokenType vrednost.
     *
     * @param op Podan operator.
     * @return Preslikana TokenType vrednost.
     */
    private static TokenType operatorMapping(String op) {       // String, saj je lahko dolžine 2
        return switch (op) {
            case "+" -> OP_ADD;
            case "-" -> OP_SUB;
            case "*" -> OP_MUL;
            case "/" -> OP_DIV;
            case "%" -> OP_MOD;
            case "&" -> OP_AND;
            case "|" -> OP_OR;
            case "!" -> OP_NOT;
            case "==" -> OP_EQ;
            case "!=" -> OP_NEQ;
            case "<" -> OP_LT;
            case ">" -> OP_GT;
            case "<=" -> OP_LEQ;
            case ">=" -> OP_GEQ;
            case "(" -> OP_LPARENT;
            case ")" -> OP_RPARENT;
            case "[" -> OP_LBRACKET;
            case "]" -> OP_RBRACKET;
            case "{" -> OP_LBRACE;
            case "}" -> OP_RBRACE;
            case ":" -> OP_COLON;
            case ";" -> OP_SEMICOLON;
            case "." -> OP_DOT;
            case "," -> OP_COMMA;
            case "=" -> OP_ASSIGN;
            default -> null;
        };
    }

    /**
     * Ustvari nov analizator.
     * 
     * @param source Izvorna koda programa.
     */
    public Lexer(String source) {
        requireNonNull(source);
        this.source = source;
    }

    /**
     * Izvedi leksikalno analizo.
     * 
     * @return Seznam leksikalnih simbolov.
     */
    public List<Symbol> scan() {
        possibleTokenType currentToken = getPossibleTokenType(source.charAt(0));
        StringBuilder lexeme = new StringBuilder();
        Location startLocation = Location.zero();
        var symbols = new ArrayList<Symbol>();
        boolean comment = false;
        Position position;
<<<<<<< HEAD
        int line = 1;
        int col = 1;
=======
        int line = 0;
        int col = 0;
>>>>>>> origin/main

        for (int i = 0; i < source.length(); i++) {
            // Nima smisla preverjati znakov, če je komentar, razen za '\n' na koncu, ki ga prekine
            if (!comment) {
                currentToken = getPossibleTokenType(source.charAt(i));
                startLocation = new Location(line, col);
                lexeme = new StringBuilder();
            }

            // V primeru komentarja ignoriraj vse znake do konca vrstice
            if (comment || currentToken == possibleTokenType.COM) {
                comment = true;
            }
            // Operator
            else if (currentToken == possibleTokenType.OP) {
<<<<<<< HEAD
                position = new Position(startLocation, new Location(line, col + 1));
=======
                position = new Position(startLocation, startLocation);
>>>>>>> origin/main
                lexeme.append(source.charAt(i));

                // Če je naslednji znak '=' potem gre za enega izmed ["==", "!=", "<=", ">="]
                if (i+1 < source.length() && source.charAt(i + 1) == '=') {
<<<<<<< HEAD
                    position = new Position(startLocation, new Location(line, ++col + 1));
=======
                    position = new Position(startLocation, new Location(line, ++col));
>>>>>>> origin/main
                    lexeme.append('=');
                    i++;
                }

                symbols.add(new Symbol(position, operatorMapping(lexeme.toString()), lexeme.toString()));
            }
            // Število
            else if (currentToken == possibleTokenType.INT) {
                lexeme.append(source.charAt(i));
                int j = i + 1;

                // Vnaprej pogleda do kod velja, da je znak celo število
                for (; j < source.length(); j++) {
                    if (getPossibleTokenType(source.charAt(j)) == possibleTokenType.INT) {
                        lexeme.append(source.charAt(j));
                        col++;
                    }
                    else
                        break;
                }

<<<<<<< HEAD
                position = new Position(startLocation, new Location(line, col + 1));
=======
                position = new Position(startLocation, new Location(line, col));
>>>>>>> origin/main
                symbols.add(new Symbol(position, C_INTEGER, lexeme.toString()));
                i = j - 1;      // Povečamo index na toliko znakov, kolikor smo jih pregledali
            }
            // Ime (zraven sodijo potencialno tudi ključne besede, atomarni tipi in logični konstanti)
            else if (currentToken == possibleTokenType.ID) {
                possibleTokenType possibleToken;
                lexeme.append(source.charAt(i));
                int j = i + 1;

                // Vnaprej pogleda do kod velja, da je znak črka angleške abecede ali celo število
                for (; j < source.length(); j++) {
                    possibleToken = getPossibleTokenType(source.charAt(j));
                    if (possibleToken == possibleTokenType.ID || possibleToken == possibleTokenType.INT) {
                        lexeme.append(source.charAt(j));
                        col++;
                    }
                    else
                        break;
                }

                TokenType actualToken = keywordMapping.get(lexeme.toString()) == null ?
                        IDENTIFIER : keywordMapping.get(lexeme.toString());
<<<<<<< HEAD
                position = new Position(startLocation, new Location(line, col + 1));
=======
                position = new Position(startLocation, new Location(line, col));
>>>>>>> origin/main
                symbols.add(new Symbol(position, actualToken, lexeme.toString()));
                i = j - 1;      // Povečamo index na toliko znakov, kolikor smo jih pregledali
            }
            // Začetek niza (znak '\'')
            else if (currentToken == possibleTokenType.STR) {
                boolean endString = false;
                char character;
                int j = i + 1;

                for (; j < source.length(); j++) {
                    character = source.charAt(j);
                    // Preveri ali je znak veljaven
                    if (32 <= character && character <= 126) {
                        col++;
                        if (character == '\'') {
                            // Če sta dva v zaporedju zapiši kot enega v nizu, sicer končaj niz
                            if (j+1 < source.length() && source.charAt(j+1) == '\'') {
                                lexeme.append('\'');
                                col++;
                                j++;
                                continue;
                            }
                            endString = true;
                            break;
                        }
                        lexeme.append(source.charAt(j));
                    }
                    else
                        break;
                }
<<<<<<< HEAD
                position = new Position(startLocation, new Location(line, col + 1));
=======
                position = new Position(startLocation, new Location(line, col));
>>>>>>> origin/main

                // Če niz ni zaprt, potem je prišlo do napake
                if (!endString)
                    Report.error(position, "neveljaven niz");

                symbols.add(new Symbol(position, C_STRING, lexeme.toString()));
                i = j;  // Povečamo index na toliko znakov, kolikor smo jih pregledali + ena (preskoči še '\'' na koncu)
            }
            // Neveljaven znak
            else if (currentToken == possibleTokenType.ERR) {
                Report.error(new Position(startLocation, startLocation), "neveljaven znak");
            }

            // Povečevanje števila vrstice in stolpca
            if (i < source.length() && source.charAt(i) == '\n') {
                comment = false;        // Ob novi vrsti je komentar neveljaven
<<<<<<< HEAD
                col = 0;
=======
                col = -1;
>>>>>>> origin/main
                line++;
            }
            col++;
        }

        // Konec programa -> EOF
        startLocation = new Location(line, col);
<<<<<<< HEAD
        symbols.add(new Symbol(new Position(startLocation, startLocation), EOF, "$"));

=======
        symbols.add(new Symbol(new Position(startLocation, startLocation), EOF, "EOF"));

        /*
        for (Symbol x : symbols) {
            System.out.println(x.toString());
        }
        */
>>>>>>> origin/main
        return symbols;
    }
}
