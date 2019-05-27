package com.netflix.fan;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import org.junit.Test;

import java.util.LinkedList;
import java.util.List;

/**
 * Created by fan.shuai on 2018/5/9.
 */
public class TestIterable {

    @Test
    public void testIterable() {

        List<String> source = new LinkedList<>();
        source.add("fan");
        source.add("moa");
        source.add("momo");
        List<String> filteredServers = Lists.newArrayList(Iterables.filter(
                source, new Predicate<String>() {
                    private String target = "fan";
                    @Override
                    public boolean apply(String input) {
                        return target.equals(input);
                    }
                }));

        System.out.println("filtered: ");
        for (String filteredServer : filteredServers) {
            System.out.print(filteredServer + "   ");
        }
        System.out.println();

        System.out.println("source after filtered: ");
        for (String s : source) {
            System.out.print(s + "   ");
        }
        System.out.println();
    }

}
