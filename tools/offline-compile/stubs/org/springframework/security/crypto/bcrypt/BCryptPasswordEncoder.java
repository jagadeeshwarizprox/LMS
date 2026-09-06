package org.springframework.security.crypto.bcrypt;
import org.springframework.security.crypto.password.PasswordEncoder;
public class BCryptPasswordEncoder implements PasswordEncoder {
    public BCryptPasswordEncoder(){} public BCryptPasswordEncoder(int strength){}
    public String encode(CharSequence raw){return raw.toString();}
    public boolean matches(CharSequence raw,String encoded){return false;}
}
