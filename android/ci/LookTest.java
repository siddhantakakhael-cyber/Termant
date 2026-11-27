package com.termant.app;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Prints what `phone look` makes of an answer of Mirage kept in a file. Run by look-check.sh. */
public final class LookTest {
    public static void main(String[] a) throws Exception {
        String answer = new String(Files.readAllBytes(Paths.get(a[0])), StandardCharsets.UTF_8);
        String text = Look.text(new JSONObject(answer), Integer.parseInt(a[1]), Integer.parseInt(a[2]), a[3], 430, "phone", "");
        System.out.write(text.getBytes(StandardCharsets.UTF_8));
        System.out.flush();
    }
}
