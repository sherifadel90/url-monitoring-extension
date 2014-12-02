package com.appdynamics.extensions.urlmonitor.config;

public class ClientConfig
{
    private int maxConnTotal = 1000;
    private int maxConnPerRoute = 1000;
    private int threadCount = 10;
    private boolean ignoreSslErrors = false;
    private boolean followRedirects = true;
    private int maxRedirects = 10;

    public int getThreadCount()
    {
        return threadCount;
    }

    public void setThreadCount(int threadCount)
    {
        this.threadCount = threadCount;
    }

    public int getMaxConnTotal()
    {
        return maxConnTotal;
    }

    public void setMaxConnTotal(int maxConnTotal)
    {
        this.maxConnTotal = maxConnTotal;
    }

    public int getMaxConnPerRoute()
    {
        return maxConnPerRoute;
    }

    public void setMaxConnPerRoute(int maxConnPerRoute)
    {
        this.maxConnPerRoute = maxConnPerRoute;
    }

    public boolean isIgnoreSslErrors()
    {
        return ignoreSslErrors;
    }

    public void setIgnoreSslErrors(boolean ignoreSslErrors)
    {
        this.ignoreSslErrors = ignoreSslErrors;
    }

    public boolean isFollowRedirects()
    {
        return followRedirects;
    }

    public void setFollowRedirects(boolean followRedirects)
    {
        this.followRedirects = followRedirects;
    }

    public int getMaxRedirects()
    {
        return maxRedirects;
    }

    public void setMaxRedirects(int maxRedirects)
    {
        this.maxRedirects = maxRedirects;
    }

    @Override
    public String toString()
    {
        return "maxConnTotal=" + maxConnTotal +
                ", maxConnPerRoute=" + maxConnPerRoute +
                ", ignoreSslErrors=" + ignoreSslErrors;
    }
}
