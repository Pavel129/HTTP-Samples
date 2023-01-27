package com.maritimebank;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class iniReader {

    public static Map< String, Properties> parseINI(String iniFileName) throws IOException {
        //По умолчанию файл setup.ini лежит в рабочем каталоге программы
        String path = new File("").getAbsolutePath() + "\\" + iniFileName;
        BufferedReader br = new BufferedReader(new FileReader(path));

        Map < String, Properties > result = new HashMap<>();
        new Properties() {
            private Properties iniSection;

            @Override
            public Object put(Object key, Object value) {
                String header = ((key) + " " + value).trim();
                if (header.startsWith("[") && header.endsWith("]"))
                    result.put( header.substring(1, header.length() - 1), iniSection = new Properties());
                else
                    iniSection.put(key, value);
                return null;
            }
        }.load(br);
        return result;
    }
}
