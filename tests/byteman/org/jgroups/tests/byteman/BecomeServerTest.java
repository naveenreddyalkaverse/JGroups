package org.jgroups.tests.byteman;

import org.jboss.byteman.contrib.bmunit.BMNGRunner;
import org.jboss.byteman.contrib.bmunit.BMScript;
import org.jgroups.Global;
import org.jgroups.JChannel;
import org.jgroups.Message;
import org.jgroups.ReceiverAdapter;
import org.jgroups.protocols.PING;
import org.jgroups.protocols.SHARED_LOOPBACK;
import org.jgroups.protocols.UNICAST2;
import org.jgroups.protocols.pbcast.GMS;
import org.jgroups.protocols.pbcast.NAKACK2;
import org.jgroups.util.Util;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

/**
 * Tests the behavior of queueing messages (in NAKACK{2}) before becoming a server
 * (https://issues.jboss.org/browse/JGRP-1522)
 * @author Bela Ban
 * @since 3.2
 */
@Test(groups=Global.BYTEMAN,sequential=true)
public class BecomeServerTest extends BMNGRunner {
    JChannel a, b;

    @AfterMethod
    protected void cleanup() {Util.close(b,a);}


    /**
     * When we flush the server queue and one or more of the delivered messages triggers a response (in the same thread),
     * we need to make sure the channel is connected, or else the JOIN will fail as the exception happens on the same
     * thread. Note that the suggested fix on JGRP-1522 will solve this. Issue: https://issues.jboss.org/browse/JGRP-1522
     */
    @BMScript(dir="scripts/BecomeServerTest", value="testSendingOfMsgsOnUnconnectedChannel")
    public void testSendingOfMsgsOnUnconnectedChannel() throws Exception {
        a=createChannel("A");
        a.setReceiver(new ReceiverAdapter() {
            public void receive(Message msg) {
                System.out.println("A: received message from " + msg.getSrc() + ": " + msg.getObject());
            }
        });
        a.connect("BecomeServerTest");

        new Thread() {
            public void run() {
                // will be blocked by byteman rendezvous
                sendMessage(a, "hello from A");
            }
        }.start();

        b=createChannel("B");
        b.setReceiver(new ReceiverAdapter() {
            public void receive(Message msg) {
                try {
                    System.out.println("B: received message from " + msg.getSrc() + ": " + msg.getObject());
                    if(msg.getSrc().equals(a.getAddress()))
                        b.send(null, "This message should trigger an exception as the channel is not yet connected");
                }
                catch(Exception e) {
                    System.err.println(e);
                }
            }
        });
        b.connect("BecomeServerTest");

        Util.waitUntilAllChannelsHaveSameSize(20000, 1000, a,b);

        Util.sleep(2000);
    }


    protected void sendMessage(JChannel ch, String message) {
        try {
            ch.send(null, message);
        }
        catch(Exception e) {
            e.printStackTrace();
        }
    }



    protected JChannel createChannel(String name) throws Exception {
        JChannel ch=Util.createChannel(new SHARED_LOOPBACK(),
                                       new PING().setValue("timeout",200).setValue("num_initial_members",2),
                                       new NAKACK2().setValue("become_server_queue_size",10),
                                       new UNICAST2(),
                                       new GMS().setValue("print_local_addr", false));
        ch.setName(name);
        return ch;
    }
}