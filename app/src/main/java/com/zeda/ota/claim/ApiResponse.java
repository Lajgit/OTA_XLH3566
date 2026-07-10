package com.zeda.ota;

public class ApiResponse<T> {

    public int code;

    public String msg;

    public T data;

    public boolean success;
}