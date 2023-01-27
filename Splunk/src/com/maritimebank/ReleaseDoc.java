package com.maritimebank;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class ReleaseDoc {

    @XmlElement
    private String id;

    @XmlAttribute
    private int count;

    public void setID(String id){
        this.id=id;
    }
}
