package org.springframework.boot;
import java.util.*;
public interface ApplicationArguments { String[] getSourceArgs(); Set<String> getOptionNames(); List<String> getNonOptionArgs(); }
