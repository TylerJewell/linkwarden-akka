package io.akka.linkwarden.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Comma-separated rows, as a Pocket export writes them.
 *
 * <p>Quoted fields may hold commas, newlines and doubled quotes; an empty line is skipped, which
 * is what the original's parser is configured to do.
 */
public final class Csv {

  private Csv() {}

  public static List<List<String>> parse(String raw) {
    List<List<String>> rows = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean quoted = false;
    boolean fieldSeen = false;

    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < raw.length() && raw.charAt(i + 1) == '"') {
            field.append('"');
            i++;
          } else {
            quoted = false;
          }
        } else {
          field.append(c);
        }
        continue;
      }
      switch (c) {
        case '"' -> {
          quoted = true;
          fieldSeen = true;
        }
        case ',' -> {
          row.add(field.toString());
          field.setLength(0);
          fieldSeen = true;
        }
        case '\r' -> {
          // a carriage return before a newline is part of the line ending
        }
        case '\n' -> {
          row.add(field.toString());
          field.setLength(0);
          if (!(row.size() == 1 && row.get(0).isEmpty() && !fieldSeen)) rows.add(row);
          row = new ArrayList<>();
          fieldSeen = false;
        }
        default -> {
          field.append(c);
          fieldSeen = true;
        }
      }
    }
    if (fieldSeen || field.length() > 0) {
      row.add(field.toString());
      rows.add(row);
    }
    return rows;
  }
}
