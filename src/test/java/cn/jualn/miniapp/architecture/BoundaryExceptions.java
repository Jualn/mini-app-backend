package cn.jualn.miniapp.architecture;

import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exact, reasoned exceptions; removed debt must be removed here, never refreshed automatically. */
final class BoundaryExceptions {
    record Entry(String status, String rule, String key, String reason) { }
    static List<Entry> load() throws Exception {
        return parse(Files.readAllLines(Path.of("src/test/resources/architecture/boundary-exceptions.tsv")));
    }
    static List<Entry> parse(List<String> lines) {
        var result=new ArrayList<Entry>();var keys=new HashSet<String>();
        for(String line:lines) {
            if(line.isBlank() || line.startsWith("#"))continue;
            String[] columns=line.split("\t",-1);
            if(columns.length!=4 || !Set.of("APPROVED","DEBT").contains(columns[0])
                    || !Set.of("signature","responsibility").contains(columns[1]) || columns[2].isBlank()
                    || columns[3].isBlank() || columns[2].contains("*") || !keys.add(columns[1]+"\t"+columns[2]))
                throw new IllegalArgumentException("Invalid/duplicate boundary exception: "+line);
            result.add(new Entry(columns[0],columns[1],columns[2],columns[3]));
        }
        return List.copyOf(result);
    }
    static void verify(String rule,Collection<String> actual,List<Entry> exceptions) {
        var allowed=new TreeSet<String>();exceptions.stream().filter(e->e.rule().equals(rule)).map(Entry::key).forEach(allowed::add);
        var unexpected=new TreeSet<>(actual);unexpected.removeAll(allowed);
        var retired=new TreeSet<>(allowed);retired.removeAll(actual);
        assertAll(()->assertTrue(unexpected.isEmpty(),()->"New "+rule+" violations:\n"+String.join("\n",unexpected)),
                ()->assertTrue(retired.isEmpty(),()->"Remove retired "+rule+" exceptions:\n"+String.join("\n",retired)));
    }
}
