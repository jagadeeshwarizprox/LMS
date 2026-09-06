package org.springframework.mail;
public class SimpleMailMessage {
    public void setTo(String... to){} public void setFrom(String from){}
    public void setSubject(String s){} public void setText(String t){}
    public void setCc(String... cc){} public void setBcc(String... bcc){} public void setReplyTo(String r){}
    public String[] getTo(){return new String[0];} public String getSubject(){return null;} public String getText(){return null;}
}
