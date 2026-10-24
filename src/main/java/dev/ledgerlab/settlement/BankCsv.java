package dev.ledgerlab.settlement;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** The complete T07 CSV contract, including quoted CR/LF and doubled quotes. */
final class BankCsv {
    private BankCsv() {}

    static Statement parse(byte[] content) {
        try {
            String text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
            List<List<String>> records = records(text);
            require(records.size() >= 2);
            var head = records.getFirst();
            var tail = records.getLast();
            require(head.size() == 7 && head.get(0).equals("H") && head.get(1).equals("BANK_STATEMENT"));
            require(head.get(2).equals("SIM-CAD-001") && head.get(6).equals("CAD"));
            LocalDate date = date(head.get(3));
            require(date.equals(date(head.get(4))));
            long version = unsigned(head.get(5));
            require(version > 0 && version <= Integer.MAX_VALUE);
            require(tail.size() == 6 && tail.get(0).equals("T"));
            var lines = new ArrayList<Line>();
            long credit = 0;
            long debit = 0;
            for (var row : records.subList(1, records.size() - 1)) {
                require(row.size() == 10 && row.getFirst().equals("D"));
                require(!row.get(1).isBlank());
                require(List.of("CREDIT", "DEBIT").contains(row.get(2)));
                long amount = unsigned(row.get(3));
                require(amount >= 1 && amount <= 99999999);
                require(row.get(4).matches("[A-Z]{3}"));
                require(List.of("BOOKED", "PENDING").contains(row.get(5)));
                Line line = new Line(
                        row.get(1),
                        row.get(2),
                        amount,
                        row.get(4),
                        row.get(5),
                        row.get(6),
                        date(row.get(7)),
                        date(row.get(8)),
                        row.get(9));
                lines.add(line);
                if (line.status().equals("BOOKED")) {
                    if (line.direction().equals("CREDIT")) credit = Math.addExact(credit, amount);
                    else debit = Math.addExact(debit, amount);
                }
            }
            require(unsigned(tail.get(1)) == lines.size());
            require(unsigned(tail.get(2)) == credit && unsigned(tail.get(3)) == debit);
            long opening = signed(tail.get(4));
            long closing = signed(tail.get(5));
            if (Math.subtractExact(Math.addExact(opening, credit), debit) != closing)
                throw new InvalidFile("BALANCE_MISMATCH");
            return new Statement(head.get(2), date, (int) version, opening, closing, List.copyOf(lines));
        } catch (InvalidFile e) {
            throw e;
        } catch (CharacterCodingException
                | IllegalArgumentException
                | java.time.DateTimeException
                | ArithmeticException e) {
            throw new InvalidFile("FILE_INCOMPLETE");
        }
    }

    private static LocalDate date(String text) {
        require(text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"));
        LocalDate date = LocalDate.parse(text);
        require(date.getYear() >= 2026 && date.getYear() <= 2027);
        return date;
    }

    private static long unsigned(String text) {
        require(text.matches("[0-9]+"));
        return signed(text);
    }

    private static long signed(String text) {
        require(text.matches("-?[0-9]+"));
        long value = Long.parseLong(text);
        require(value >= -9007199254740991L && value <= 9007199254740991L);
        return value;
    }

    private static void require(boolean condition) {
        if (!condition) throw new InvalidFile("FILE_INCOMPLETE");
    }

    private static List<List<String>> records(String text) {
        var result = new ArrayList<List<String>>();
        var row = new ArrayList<String>();
        var field = new StringBuilder();
        boolean quoted = false;
        boolean closed = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            require(c != '\0');
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                        closed = true;
                    }
                } else field.append(c);
            } else if (c == ',' || c == '\r') {
                row.add(field.toString());
                field.setLength(0);
                closed = false;
                if (c == '\r') {
                    require(i + 1 < text.length() && text.charAt(++i) == '\n');
                    result.add(List.copyOf(row));
                    row.clear();
                }
            } else if (c == '"') {
                require(field.isEmpty() && !closed);
                quoted = true;
            } else {
                require(!closed && c != '\n');
                field.append(c);
            }
        }
        require(!quoted && !closed && field.isEmpty() && row.isEmpty() && text.endsWith("\r\n"));
        return result;
    }

    record Statement(String account, LocalDate date, int version, long opening, long closing, List<Line> lines) {}

    record Line(
            String id,
            String direction,
            long amount,
            String currency,
            String status,
            String reference,
            LocalDate bookingDate,
            LocalDate valueDate,
            String memo) {
        boolean sameExceptStatus(Line other) {
            return new Line(id, direction, amount, currency, other.status, reference, bookingDate, valueDate, memo)
                    .equals(other);
        }
    }

    static final class InvalidFile extends RuntimeException {
        InvalidFile(String reason) {
            super(reason);
        }
    }
}
