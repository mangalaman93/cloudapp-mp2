import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.ArrayWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.KeyValueTextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import java.io.IOException;
import java.lang.Integer;
import java.util.StringTokenizer;
import java.util.TreeSet;
import java.io.InputStreamReader;
import java.util.*;
import java.io.BufferedReader;

public class PopularityLeague extends Configured implements Tool {
    public static void main(String[] args) throws Exception {
        int res = ToolRunner.run(new Configuration(), new PopularityLeague(), args);
        System.exit(res);
    }

    public static class IntArrayWritable extends ArrayWritable {
        public IntArrayWritable() {
            super(IntWritable.class);
        }

        public IntArrayWritable(Integer[] numbers) {
            super(IntWritable.class);
            IntWritable[] ints = new IntWritable[numbers.length];
            for (int i = 0; i < numbers.length; i++) {
                ints[i] = new IntWritable(numbers[i]);
            }
            set(ints);
        }
    }

    @Override
    public int run(String[] args) throws Exception {
        // TODO
        Configuration conf = this.getConf();
        FileSystem fs = FileSystem.get(conf);
        Path tmpPath = new Path("/mp2/tmp");
        fs.delete(tmpPath, true);

        Job jobA = Job.getInstance(conf, "Link Count");
        jobA.setOutputKeyClass(IntWritable.class);
        jobA.setOutputValueClass(IntWritable.class);

        jobA.setMapperClass(LinkCountMap.class);
        jobA.setReducerClass(LinkCountReduce.class);

        FileInputFormat.setInputPaths(jobA, new Path(args[0]));
        FileOutputFormat.setOutputPath(jobA, tmpPath);

        jobA.setJarByClass(PopularityLeague.class);
        jobA.waitForCompletion(true);

        Job jobB = Job.getInstance(conf, "Top Links");
        jobB.setOutputKeyClass(IntWritable.class);
        jobB.setOutputValueClass(IntWritable.class);

        jobB.setMapOutputKeyClass(NullWritable.class);
        jobB.setMapOutputValueClass(IntArrayWritable.class);

        jobB.setMapperClass(TopLinksMap.class);
        jobB.setReducerClass(TopLinksReduce.class);
        jobB.setNumReduceTasks(1);

        FileInputFormat.setInputPaths(jobB, tmpPath);
        FileOutputFormat.setOutputPath(jobB, new Path(args[1]));

        jobB.setInputFormatClass(KeyValueTextInputFormat.class);
        jobB.setOutputFormatClass(TextOutputFormat.class);

        jobB.setJarByClass(PopularityLeague.class);
        return jobB.waitForCompletion(true) ? 0 : 1;
    }

    public static List<Integer> readHDFSFile(String path, Configuration conf) throws IOException{
        Path pt=new Path(path);
        FileSystem fs = FileSystem.get(pt.toUri(), conf);
        FSDataInputStream file = fs.open(pt);
        BufferedReader buffIn = new BufferedReader(new InputStreamReader(file));

        List<Integer> everything = new ArrayList<Integer>();
        String line;
        while ((line = buffIn.readLine()) != null) {
            everything.add(Integer.parseInt(line.trim()));
        }

        return everything;
    }

    public static class LinkCountMap extends Mapper<Object, Text, IntWritable, IntWritable> {
        // TODO
        @Override
        public void map(Object key, Text value, Context context) throws IOException, InterruptedException {
            String line = value.toString();
            StringTokenizer st = new StringTokenizer(line, "\t :");
            int cur_page = Integer.parseInt(st.nextToken());
            while(st.hasMoreTokens()) {
                 int link_to_page = Integer.parseInt(st.nextToken());
                 context.write(new IntWritable(link_to_page), new IntWritable(1));
            }

            context.write(new IntWritable(cur_page), new IntWritable(0));
        }
    }

    public static class LinkCountReduce extends Reducer<IntWritable, IntWritable, IntWritable, IntWritable> {
        // TODO
        @Override
        public void reduce(IntWritable key, Iterable<IntWritable> values, Context context) throws IOException, InterruptedException {
            int sum = 0;
            for (IntWritable val : values) {
                sum += val.get();
            }

            context.write(key, new IntWritable(sum));
        }
    }

    public static class TopLinksMap extends Mapper<Text, Text, NullWritable, IntArrayWritable> {
        Integer N;
        private TreeSet<Pair<Integer, Integer>> countToIdMap = new TreeSet<Pair<Integer, Integer>>();
        List<Integer> leagueList;

        @Override
        protected void setup(Context context) throws IOException,InterruptedException {
            Configuration conf = context.getConfiguration();
            this.N = conf.getInt("N", 10);

            String leagueListPath = conf.get("league");
            this.leagueList = readHDFSFile(leagueListPath, conf);
        }

        // TODO
        @Override
        public void map(Text key, Text value, Context context) throws IOException, InterruptedException {
            Integer id = Integer.parseInt(key.toString());
            if (!leagueList.contains(id)) {
                return;
            }

            Integer count = Integer.parseInt(value.toString());
            countToIdMap.add(new Pair<Integer, Integer>(count, id));

            if(countToIdMap.size() > N) {
                countToIdMap.remove(countToIdMap.first());
            }
        }

        @Override
        protected void cleanup(Context context) throws IOException, InterruptedException {
            for(Pair<Integer, Integer> item : countToIdMap) {
                Integer[] ints = {item.second, item.first};
                IntArrayWritable val = new IntArrayWritable(ints);
                context.write(NullWritable.get(), val);
            }
        }
    }

    public static class TopLinksReduce extends Reducer<NullWritable, IntArrayWritable, IntWritable, IntWritable> {
        Integer N;
        private TreeSet<Pair<Integer, Integer>> countToIdMap = new TreeSet<Pair<Integer, Integer>>();

        @Override
        protected void setup(Context context) throws IOException,InterruptedException {
            Configuration conf = context.getConfiguration();
            this.N = conf.getInt("N", 10);
        }

        // TODO
        @Override
        public void reduce(NullWritable key, Iterable<IntArrayWritable> values, Context context) throws IOException, InterruptedException {
            for (IntArrayWritable val: values) {
                IntWritable[] pair = (IntWritable[]) val.toArray();
                countToIdMap.add(new Pair<Integer, Integer>(pair[1].get(), pair[0].get()));

                if (countToIdMap.size() > N) {
                    countToIdMap.remove(countToIdMap.first());
                }
            }

            int last_value = -1;
            int rank = -1;
            int count = 0;
            for (Pair<Integer, Integer> item: countToIdMap) {
                if (last_value != item.first) {
                    rank = count;
                    last_value = item.first;
                }

                context.write(new IntWritable(item.second), new IntWritable(rank));
                count += 1;
            }
        }
    }
}

class Pair<A extends Comparable<? super A>,
        B extends Comparable<? super B>>
        implements Comparable<Pair<A, B>> {

    public final A first;
    public final B second;

    public Pair(A first, B second) {
        this.first = first;
        this.second = second;
    }

    public static <A extends Comparable<? super A>,
            B extends Comparable<? super B>>
    Pair<A, B> of(A first, B second) {
        return new Pair<A, B>(first, second);
    }

    @Override
    public int compareTo(Pair<A, B> o) {
        int cmp = o == null ? 1 : (this.first).compareTo(o.first);
        return cmp == 0 ? (this.second).compareTo(o.second) : cmp;
    }

    @Override
    public int hashCode() {
        return 31 * hashcode(first) + hashcode(second);
    }

    private static int hashcode(Object o) {
        return o == null ? 0 : o.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Pair))
            return false;
        if (this == obj)
            return true;
        return equal(first, ((Pair<?, ?>) obj).first)
                && equal(second, ((Pair<?, ?>) obj).second);
    }

    private boolean equal(Object o1, Object o2) {
        return o1 == o2 || (o1 != null && o1.equals(o2));
    }

    @Override
    public String toString() {
        return "(" + first + ", " + second + ')';
    }
}
