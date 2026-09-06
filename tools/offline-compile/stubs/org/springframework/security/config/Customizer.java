package org.springframework.security.config;
@FunctionalInterface public interface Customizer<T> { void customize(T t); static <T> Customizer<T> withDefaults(){return t->{};} }
