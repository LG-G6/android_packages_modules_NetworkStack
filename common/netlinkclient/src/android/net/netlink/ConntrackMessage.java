/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net.netlink;

import static android.net.netlink.StructNlAttr.findNextAttrOfType;
import static android.net.netlink.StructNlAttr.makeNestedType;
import static android.net.netlink.StructNlMsgHdr.NLM_F_ACK;
import static android.net.netlink.StructNlMsgHdr.NLM_F_REPLACE;
import static android.net.netlink.StructNlMsgHdr.NLM_F_REQUEST;

import static java.nio.ByteOrder.BIG_ENDIAN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.system.OsConstants;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;


/**
 * A NetlinkMessage subclass for netlink conntrack messages.
 *
 * see also: &lt;linux_src&gt;/include/uapi/linux/netfilter/nfnetlink_conntrack.h
 *
 * @hide
 */
public class ConntrackMessage extends NetlinkMessage {
    public static final int STRUCT_SIZE = StructNlMsgHdr.STRUCT_SIZE + StructNfGenMsg.STRUCT_SIZE;

    // enum ctattr_type
    public static final short CTA_TUPLE_ORIG  = 1;
    public static final short CTA_TUPLE_REPLY = 2;
    public static final short CTA_STATUS      = 3;
    public static final short CTA_TIMEOUT     = 7;

    // enum ctattr_tuple
    public static final short CTA_TUPLE_IP    = 1;
    public static final short CTA_TUPLE_PROTO = 2;

    // enum ctattr_ip
    public static final short CTA_IP_V4_SRC = 1;
    public static final short CTA_IP_V4_DST = 2;

    // enum ctattr_l4proto
    public static final short CTA_PROTO_NUM      = 1;
    public static final short CTA_PROTO_SRC_PORT = 2;
    public static final short CTA_PROTO_DST_PORT = 3;

    /**
     * A tuple for the conntrack connection information.
     *
     * see also CTA_TUPLE_ORIG and CTA_TUPLE_REPLY.
     */
    public static class Tuple {
        public final Inet4Address srcIp;
        public final Inet4Address dstIp;
        // TODO: tuple proto for CTA_TUPLE_PROTO.

        public Tuple(TupleIpv4 ip) {
            this.srcIp = ip.src;
            this.dstIp = ip.dst;
        }
    }

    /**
     * A tuple for the conntrack connection address.
     *
     * see also CTA_TUPLE_IP.
     */
    public static class TupleIpv4 {
        public final Inet4Address src;
        public final Inet4Address dst;

        public TupleIpv4(Inet4Address src, Inet4Address dst) {
            this.src = src;
            this.dst = dst;
        }
    }

    public static byte[] newIPv4TimeoutUpdateRequest(
            int proto, Inet4Address src, int sport, Inet4Address dst, int dport, int timeoutSec) {
        // *** STYLE WARNING ***
        //
        // Code below this point uses extra block indentation to highlight the
        // packing of nested tuple netlink attribute types.
        final StructNlAttr ctaTupleOrig = new StructNlAttr(CTA_TUPLE_ORIG,
                new StructNlAttr(CTA_TUPLE_IP,
                        new StructNlAttr(CTA_IP_V4_SRC, src),
                        new StructNlAttr(CTA_IP_V4_DST, dst)),
                new StructNlAttr(CTA_TUPLE_PROTO,
                        new StructNlAttr(CTA_PROTO_NUM, (byte) proto),
                        new StructNlAttr(CTA_PROTO_SRC_PORT, (short) sport, BIG_ENDIAN),
                        new StructNlAttr(CTA_PROTO_DST_PORT, (short) dport, BIG_ENDIAN)));

        final StructNlAttr ctaTimeout = new StructNlAttr(CTA_TIMEOUT, timeoutSec, BIG_ENDIAN);

        final int payloadLength = ctaTupleOrig.getAlignedLength() + ctaTimeout.getAlignedLength();
        final byte[] bytes = new byte[STRUCT_SIZE + payloadLength];
        final ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
        byteBuffer.order(ByteOrder.nativeOrder());

        final ConntrackMessage ctmsg = new ConntrackMessage();
        ctmsg.mHeader.nlmsg_len = bytes.length;
        ctmsg.mHeader.nlmsg_type = (NetlinkConstants.NFNL_SUBSYS_CTNETLINK << 8)
                | NetlinkConstants.IPCTNL_MSG_CT_NEW;
        ctmsg.mHeader.nlmsg_flags = NLM_F_REQUEST | NLM_F_ACK | NLM_F_REPLACE;
        ctmsg.mHeader.nlmsg_seq = 1;
        ctmsg.pack(byteBuffer);

        ctaTupleOrig.pack(byteBuffer);
        ctaTimeout.pack(byteBuffer);

        return bytes;
    }

    /**
     * Parses a netfilter conntrack message from a {@link ByteBuffer}.
     *
     * @param header the netlink message header.
     * @param byteBuffer The buffer from which to parse the netfilter conntrack message.
     * @return the parsed netfilter conntrack message, or {@code null} if the netfilter conntrack
     *         message could not be parsed successfully (for example, if it was truncated).
     */
    public static ConntrackMessage parse(StructNlMsgHdr header, ByteBuffer byteBuffer) {
        // Just build the netlink header and netfilter header for now and pretend the whole message
        // was consumed.
        // TODO: Parse the conntrack attributes.
        final StructNfGenMsg nfGenMsg = StructNfGenMsg.parse(byteBuffer);
        if (nfGenMsg == null) {
            return null;
        }

        final int baseOffset = byteBuffer.position();
        StructNlAttr nlAttr = findNextAttrOfType(CTA_STATUS, byteBuffer);
        int status = 0;
        if (nlAttr != null) {
            status = nlAttr.getValueAsBe32(0);
        }

        byteBuffer.position(baseOffset);
        nlAttr = findNextAttrOfType(CTA_TIMEOUT, byteBuffer);
        int timeoutSec = 0;
        if (nlAttr != null) {
            timeoutSec = nlAttr.getValueAsBe32(0);
        }

        byteBuffer.position(baseOffset);
        nlAttr = findNextAttrOfType(makeNestedType(CTA_TUPLE_ORIG), byteBuffer);
        Tuple tupleOrig = null;
        if (nlAttr != null) {
            tupleOrig = parseTuple(nlAttr.getValueAsByteBuffer());
        }

        byteBuffer.position(baseOffset);
        nlAttr = findNextAttrOfType(makeNestedType(CTA_TUPLE_REPLY), byteBuffer);
        Tuple tupleReply = null;
        if (nlAttr != null) {
            tupleReply = parseTuple(nlAttr.getValueAsByteBuffer());
        }

        // Advance to the end of the message.
        byteBuffer.position(baseOffset);
        final int kMinConsumed = StructNlMsgHdr.STRUCT_SIZE + StructNfGenMsg.STRUCT_SIZE;
        final int kAdditionalSpace = NetlinkConstants.alignedLengthOf(
                header.nlmsg_len - kMinConsumed);
        if (byteBuffer.remaining() < kAdditionalSpace) {
            return null;
        }
        byteBuffer.position(baseOffset + kAdditionalSpace);

        return new ConntrackMessage(header, nfGenMsg, tupleOrig, tupleReply, status, timeoutSec);
    }

    /**
     * Parses a conntrack tuple from a {@link ByteBuffer}.
     *
     * The attribute parsing is interesting on:
     * - CTA_TUPLE_IP
     *     CTA_IP_V4_SRC
     *     CTA_IP_V4_DST
     * - CTA_TUPLE_PROTO
     *     CTA_PROTO_NUM
     *     CTA_PROTO_SRC_PORT
     *     CTA_PROTO_DST_PORT
     *
     * Assume that the minimum size is the sum of CTA_TUPLE_IP (size: 20) and CTA_TUPLE_PROTO
     * (size: 28). Here is an example for an expected CTA_TUPLE_ORIG message in raw data:
     * +--------------------------------------------------------------------------------------+
     * | CTA_TUPLE_ORIG                                                                       |
     * +--------------------------+-----------------------------------------------------------+
     * | 1400                     | nla_len = 20                                              |
     * | 0180                     | nla_type = nested CTA_TUPLE_IP                            |
     * |     0800 0100 C0A8500C   |     nla_type=CTA_IP_V4_SRC, ip=192.168.80.12              |
     * |     0800 0200 8C700874   |     nla_type=CTA_IP_V4_DST, ip=140.112.8.116              |
     * | 1C00                     | nla_len = 28                                              |
     * | 0280                     | nla_type = nested CTA_TUPLE_PROTO                         |
     * |     0500 0100 06 000000  |     nla_type=CTA_PROTO_NUM, proto=IPPROTO_TCP (6)         |
     * |     0600 0200 F3F1 0000  |     nla_type=CTA_PROTO_SRC_PORT, port=62449 (big endian)  |
     * |     0600 0300 01BB 0000  |     nla_type=CTA_PROTO_DST_PORT, port=433 (big endian)    |
     * +--------------------------+-----------------------------------------------------------+
     *
     * The position of the byte buffer doesn't set to the end when the function returns. It is okay
     * because the caller ConntrackMessage#parse has passed a copy which is used for this parser
     * only. Moreover, the parser behavior is the same as other existing netlink struct class
     * parser. Ex: StructInetDiagMsg#parse.
     */
    @Nullable
    private static Tuple parseTuple(@Nullable ByteBuffer byteBuffer) {
        if (byteBuffer == null) return null;

        TupleIpv4 tupleIpv4 = null;

        final int baseOffset = byteBuffer.position();
        StructNlAttr nlAttr = findNextAttrOfType(makeNestedType(CTA_TUPLE_IP), byteBuffer);
        if (nlAttr != null) {
            tupleIpv4 = parseTupleIpv4(nlAttr.getValueAsByteBuffer());
        }
        if (tupleIpv4 == null) return null;

        return new Tuple(tupleIpv4);
    }

    @Nullable
    private static Inet4Address castToInet4Address(@Nullable InetAddress address) {
        if (address == null || !(address instanceof Inet4Address)) return null;
        return (Inet4Address) address;
    }

    @Nullable
    private static TupleIpv4 parseTupleIpv4(@Nullable ByteBuffer byteBuffer) {
        if (byteBuffer == null) return null;

        Inet4Address src = null;
        Inet4Address dst = null;

        final int baseOffset = byteBuffer.position();
        StructNlAttr nlAttr = findNextAttrOfType(CTA_IP_V4_SRC, byteBuffer);
        if (nlAttr != null) {
            src = castToInet4Address(nlAttr.getValueAsInetAddress());
        }
        if (src == null) return null;

        byteBuffer.position(baseOffset);
        nlAttr = findNextAttrOfType(CTA_IP_V4_DST, byteBuffer);
        if (nlAttr != null) {
            dst = castToInet4Address(nlAttr.getValueAsInetAddress());
        }
        if (dst == null) return null;

        return new TupleIpv4(src, dst);
    }

    /**
     * Netfilter header.
     */
    public final StructNfGenMsg nfGenMsg;
    /**
     * Original direction conntrack tuple.
     *
     * The tuple is determined by the parsed attribute value CTA_TUPLE_ORIG, or null if the
     * tuple could not be parsed successfully (for example, if it was truncated or absent).
     */
    @Nullable
    public final Tuple tupleOrig;
    /**
     * Reply direction conntrack tuple.
     *
     * The tuple is determined by the parsed attribute value CTA_TUPLE_REPLY, or null if the
     * tuple could not be parsed successfully (for example, if it was truncated or absent).
     */
    @Nullable
    public final Tuple tupleReply;
    /**
     * Connection status. A bitmask of ip_conntrack_status enum flags.
     *
     * The status is determined by the parsed attribute value CTA_STATUS, or 0 if the status could
     * not be parsed successfully (for example, if it was truncated or absent). For the message
     * from kernel, the valid status is non-zero. For the message from user space, the status may
     * be 0 (absent).
     */
    public final int status;
    /**
     * Conntrack timeout.
     *
     * The timeout is determined by the parsed attribute value CTA_TIMEOUT, or 0 if the timeout
     * could not be parsed successfully (for example, if it was truncated or absent). For
     * IPCTNL_MSG_CT_NEW event, the valid timeout is non-zero. For IPCTNL_MSG_CT_DELETE event, the
     * timeout is 0 (absent).
     */
    public final int timeoutSec;

    private ConntrackMessage() {
        super(new StructNlMsgHdr());
        nfGenMsg = new StructNfGenMsg((byte) OsConstants.AF_INET);

        // This constructor is only used by #newIPv4TimeoutUpdateRequest which doesn't use these
        // data member for packing message. Simply fill them to null or 0.
        tupleOrig = null;
        tupleReply = null;
        status = 0;
        timeoutSec = 0;
    }

    private ConntrackMessage(@NonNull StructNlMsgHdr header, @NonNull StructNfGenMsg nfGenMsg,
            @Nullable Tuple tupleOrig, @Nullable Tuple tupleReply, int status, int timeoutSec) {
        super(header);
        this.nfGenMsg = nfGenMsg;
        this.tupleOrig = tupleOrig;
        this.tupleReply = tupleReply;
        this.status = status;
        this.timeoutSec = timeoutSec;
    }

    public void pack(ByteBuffer byteBuffer) {
        mHeader.pack(byteBuffer);
        nfGenMsg.pack(byteBuffer);
    }
}
