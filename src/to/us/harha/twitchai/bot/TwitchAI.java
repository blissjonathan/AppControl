package to.us.harha.twitchai.bot;

import static to.us.harha.twitchai.util.Globals.*;
import static to.us.harha.twitchai.util.LogUtils.logMsg;
import static to.us.harha.twitchai.util.LogUtils.logErr;
import static to.us.harha.twitchai.util.GenUtils.exit;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.*;
import java.security.Key;
import java.util.Arrays;

import com.sun.jna.*;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.win32.*;


import java.util.ArrayList;

import org.jibble.pircbot.IrcException;
import org.jibble.pircbot.PircBot;
import org.jibble.pircbot.User;

import to.us.harha.twitchai.Config;
import to.us.harha.twitchai.util.FileUtils;

public class TwitchAI extends PircBot {

    private float m_cycleTime;
    private float m_cmdTime;
    private boolean m_hasMembership;
    private boolean m_hasCommands;
    private boolean m_hasTags;
    private ArrayList<TwitchUser> m_moderators;
    private ArrayList<TwitchChannel> m_channels;
    public boolean enableChat;
    public static int[] bounds;
    public boolean cd;
    public int ctime = 0;
    public boolean logging = false;

    public TwitchAI() {
        m_cycleTime = 0.0f;
        m_cmdTime = 0.0f;
        m_hasMembership = false;
        m_hasCommands = false;
        m_hasTags = true;
        m_moderators = new ArrayList<TwitchUser>();
        m_channels = new ArrayList<TwitchChannel>();
        boolean enableChat = false;
        int[] bounds = getDimensions();

        setName(g_bot_name);
        setVersion(g_lib_version);
        setVerbose(false);
    }

    public void init_twitch() {
        logMsg("Loading all registered TwitchAI moderators...");
        ArrayList<String> loadedModerators = FileUtils.readTextFile("data/moderators.txt");
        for (String m : loadedModerators) {
            String[] m_split = m.split(" ");
            TwitchUser newmod = new TwitchUser(m_split[0], m_split[1]);
            logMsg("Added a TwitchAI moderator (" + newmod + ") to m_moderators");
            m_moderators.add(newmod);
        }

        logMsg("Attempting to connect to irc.twitch.tv...");
        try {
            connect("irc.twitch.tv", 6667, g_bot_oauth);
        } catch (IOException | IrcException e) {
            logErr(e.getStackTrace().toString());
            exit(1);
        }

        if (g_bot_reqMembership) {
            logMsg("Requesting twitch membership capability for NAMES/JOIN/PART/MODE messages...");
            sendRawLine(g_server_memreq);
        } else {
            logMsg("Membership request is disabled!");
            m_hasMembership = true;
        }

        if (g_bot_reqCommands) {
            logMsg("Requesting twitch commands capability for NOTICE/HOSTTARGET/CLEARCHAT/USERSTATE messages... ");
            sendRawLine(g_server_cmdreq);
        } else {
            logMsg("Commands request is disabled!");
            m_hasCommands = true;
        }

        if (g_bot_reqTags) {
            logMsg("Requesting twitch tags capability for PRIVMSG/USERSTATE/GLOBALUSERSTATE messages... ");
            sendRawLine(g_server_tagreq);
        } else {
            logMsg("Tags request is disabled!");
            m_hasTags = true;
        }
    }

    public void init_channels() {
        logMsg("Attempting to join all registered channels...");
        ArrayList<String> loadedChannels = FileUtils.readTextFile("data/channels.txt");
        for (String c : loadedChannels) {
            if (!c.startsWith("#")) {
                c = "#" + c;
            }
            joinToChannel(c);
        }
    }

    public void joinToChannel(String channel) {
        logMsg("Attempting to join channel " + channel);
        joinChannel(channel);
        m_channels.add(new TwitchChannel(channel));
    }

    public void partFromChannel(String channel) {
        logMsg("Attempting to part from channel " + channel);
        partChannel(channel);
        m_channels.remove(getTwitchChannel(channel));
    }

    public void addChannel(String channel, String sender, String addChan) {
        ArrayList<String> addchan_channels = FileUtils.readTextFile("data/channels.txt");
        if (addchan_channels.size() <= 0 || !addchan_channels.contains(addChan)) {
            logMsg("Registering a new channel: " + addChan);
            sendTwitchMessage(channel, "Registering a new channel: " + addChan);
            FileUtils.writeToTextFile("data/", "channels.txt", addChan);
            joinToChannel(addChan);
        } else {
            logErr("Failed to register a new channel: " + addChan);
            sendTwitchMessage(channel, "That channel is already registered!");
        }
        return;
    }

    public void delChannel(String channel, String sender, String delChan) {
        if (!Arrays.asList(getChannels()).contains(delChan)) {
            logErr("Can't delete channel " + delChan + " from the global channels list because it isn't in the joined channels list!");
            return;
        }
        logMsg(sender + " Requested a deletion of channel: " + delChan);
        sendTwitchMessage(channel, sender + " Requested a deletion of channel: " + delChan);
        partFromChannel(delChan);
        FileUtils.removeFromTextFile("data", "/channels.txt", delChan);
    }

    public void sendTwitchMessage(String channel, String message) {
        TwitchChannel twitch_channel = getTwitchChannel(channel);
        TwitchUser twitch_user = twitch_channel.getUser(g_bot_name);

        if (twitch_user == null) {
            twitch_user = g_nulluser;
        }

        if (twitch_user.isOperator()) {
            if (twitch_channel.getCmdSent() <= 48) {
                twitch_channel.setCmdSent(twitch_channel.getCmdSent() + 1);
                sendMessage(channel, message);
            } else {
                logErr("Cannot send a message to channel (" + twitch_channel + ")! 100 Messages per 30s limit nearly exceeded! (" + twitch_channel.getCmdSent() + ")");
            }
        } else {
            if (twitch_channel.getCmdSent() <= 16) {
                twitch_channel.setCmdSent(twitch_channel.getCmdSent() + 1);
                sendMessage(channel, message);
            } else {
                logErr("Cannot send a message to channel (" + twitch_channel + ")! 20 Messages per 30s limit nearly exceeded! (" + twitch_channel.getCmdSent() + ")");
            }
        }
    }

    @Override
    public void handleLine(String line) {
        logMsg("handleLine | " + line);

        super.handleLine(line);

        if (!isInitialized()) {
            if (line.equals(g_server_memans)) {
                m_hasMembership = true;
            }

            if (line.equals(g_server_cmdans)) {
                m_hasCommands = true;
            }

            if (line.equals(g_server_tagans)) {
                m_hasTags = true;
            }
        }

        if (line.contains(":jtv ")) {
            line = line.replace(":jtv ", "");
            String[] line_array = line.split(" ");

            if (line_array[0].equals("MODE") && line_array.length >= 4) {
                onMode(line_array[1], line_array[3], line_array[3], "", line_array[2]);
            }
        }
    }

    @Override
    public void onUserList(String channel, User[] users) {
        super.onUserList(channel, users);

        TwitchChannel twitch_channel = getTwitchChannel(channel);

        if (twitch_channel == null) {
            logErr("Error on USERLIST, channel (" + channel + ") doesn't exist!");
            return;
        }

        for (User u : users) {
            if (twitch_channel.getUser(u.getNick()) == null) {
                TwitchUser twitch_mod = getOfflineModerator(u.getNick());
                String prefix = "";
                if (twitch_mod != null) {
                    prefix = twitch_mod.getPrefix();
                }
                TwitchUser user = new TwitchUser(u.getNick(), prefix);
                twitch_channel.addUser(user);
                logMsg("Adding new user (" + user + ") to channel (" + twitch_channel.toString() + ")");
            }
        }
    }

    @Override
    public void onJoin(String channel, String sender, String login, String hostname) {
        super.onJoin(channel, sender, login, hostname);

        TwitchChannel twitch_channel = getTwitchChannel(channel);
        TwitchUser twitch_user = twitch_channel.getUser(sender);
        TwitchUser twitch_mod = getOfflineModerator(sender);

        if (twitch_channel != null && twitch_user == null) {
            String prefix = "";
            if (twitch_mod != null) {
                prefix = twitch_mod.getPrefix();
            }
            TwitchUser user = new TwitchUser(sender, prefix);
            twitch_channel.addUser(user);
            logMsg("Adding new user (" + user + ") to channel (" + twitch_channel.toString() + ")");
        }
    }

    @Override
    public void onPart(String channel, String sender, String login, String hostname) {
        super.onPart(channel, sender, login, hostname);

        TwitchChannel twitch_channel = getTwitchChannel(channel);
        TwitchUser twitch_user = twitch_channel.getUser(sender);

        if (twitch_channel != null && twitch_user != null) {
            twitch_channel.delUser(twitch_user);
            logMsg("Removing user (" + twitch_user + ") from channel (" + twitch_channel.toString() + ")");
        }
    }

    @Override
    public void onMode(String channel, String sourceNick, String sourceLogin, String sourceHostname, String mode) {
        super.onMode(channel, sourceNick, sourceLogin, sourceHostname, mode);

        TwitchChannel twitch_channel = getTwitchChannel(channel);
        TwitchUser twitch_user = twitch_channel.getUser(sourceNick);

        if (twitch_user == null) {
            logErr("Error on MODE, cannot find (" + twitch_user + ") from channel (" + twitch_channel.toString() + ")");
            return;
        }

        if (mode.equals("+o")) {
            logMsg("Adding +o MODE for user (" + twitch_user + ") in channel (" + twitch_channel.toString() + ")");
            twitch_user.addPrefixChar("@");
        } else if (mode.equals("-o")) {
            logMsg("Adding -o MODE for user (" + twitch_user + ") in channel (" + twitch_channel.toString() + ")");
            twitch_user.delPrefixChar("@");
        }
    }

    @Override
    public void onMessage(String channel, String sender, String login, String hostname, String message) {
        logMsg("data/channels/" + channel, "/onMessage", "User: " + sender + " Hostname: " + hostname + " Message: " + message);

        TwitchChannel twitch_channel = getTwitchChannel(channel);

        /*
         * Handle all chat commands
         */
        if (message.startsWith("!")) {

            TwitchUser twitch_user = twitch_channel.getUser(sender);

            if (twitch_user == null) {
                logErr("Error on ONMESSAGE, user (" + sender + ") doesn't exist! Creating a temp null user object for user!");
                twitch_user = g_nulluser;
            }

            if (message.length() > 3) {
                if (twitch_user.getCmdTimer() > 0) {
                    if (twitch_user.getCmdTimer() > 10 && twitch_channel.getCmdSent() < 32) {
                        sendTwitchMessage(channel, twitch_user + " Please wait " + twitch_user.getCmdTimer() + " seconds before sending a new command.");
                    }
                    twitch_user.setCmdTimer(twitch_user.getCmdTimer() + 5);
                    return;
                } else {
                    if (!twitch_user.getName().equals("null")) {
                        twitch_user.setCmdTimer(5);
                    }
                }
            }

            message = message.replace("!", "");
            String[] msg_array = message.split(" ");
            String msg_command = msg_array[0];
            String user_sender = sender;
            String user_target;
            String chan_sender = channel;
            String chan_target;
            float time;
            long timeStart, timeEnd;

            timeStart = System.nanoTime();

            /*
             * Commands available on the bot's own channel
             */
            if (channel.equals(g_bot_chan)) {
                switch (msg_command) {
                    case "help":
                        sendTwitchMessage(channel, "List of available commands on this channel: " + g_commands_bot);
                        break;
                }
            }

            /*
             * Commands available on all channels
             */
            switch (msg_command) {

                /*
                 * Normal channel user commands below
                 */
                case "help":
                    String help_text = "List of available commands:" + g_commands_user;
                    sendTwitchMessage(channel, help_text);
                    break;

                case "info":
                    sendTwitchMessage(channel, "Language: Java Core: " + g_bot_version + " Library: " + getVersion());
                    break;

                case "performance":
                    sendTwitchMessage(channel, "My current main loop cycle time: " + m_cycleTime + "ms. My current cmd loop cycle time: " + m_cmdTime + "ms.");
                    break;

                case "date":
                    sendTwitchMessage(channel, g_dateformat.format(g_date));
                    break;

                case "time":
                    sendTwitchMessage(channel, g_timeformat.format(g_date));
                    break;
                case "dims":
                    if (!sender.equalsIgnoreCase(Config.Channel)) {
                        break;
                    }
                    sendTwitchMessage(channel, Arrays.toString(getDimensions()));
                    break;
                case "setcooldown":
                    if (!sender.equalsIgnoreCase(Config.Channel)) {
                        break;
                    }
                    if (msg_array.length == 2) {
                        ctime = Integer.parseInt(msg_array[1]);
                    }
                    break;
                case "enable":
                    if (!sender.equalsIgnoreCase(Config.Channel)) {
                        break;
                    }
                    enableChat = true;
                    sendTwitchMessage(channel, "Chat Enabled");
                    break;
                case "disable":
                    if (!sender.equalsIgnoreCase(Config.Channel)) {
                        break;
                    }
                    enableChat = false;
                    sendTwitchMessage(channel, "Chat Disabled");
                    break;
                case "t":
                case "type":
                    if (enableChat || sender.equalsIgnoreCase(Config.Channel)) {
                        if (checkLogin()) {
                            if (msg_array.length == 2) {
                                if (msg_array[1].matches("\\d+")) {
                                    type(null, msg_array[1].replaceAll("\\D+", ""));
                                }
                            }
                        } else {
                            sendTwitchMessage(channel, "Not logged in (!login)");
                        }
                    }
                    break;
                case "m":
                case "move":
                    if (enableChat || sender.equalsIgnoreCase(Config.Channel)) {
                        if (checkLogin()) {
                            logMsg(Integer.toString(msg_array.length));
                            if (msg_array.length == 3) {
                                if (msg_array[1].matches("\\d+")) {
                                    if (msg_array[2].matches("\\d+")) {
                                        try {
                                            int x = Integer.parseInt(msg_array[1].replaceAll("\\D+", ""));
                                            int y = Integer.parseInt(msg_array[2].replaceAll("\\D+", ""));
                                            move(null, x, y);
                                        } catch (Exception e) {
                                            e.printStackTrace();
                                        }
                                    }
                                }
                            }
                        } else {
                            sendTwitchMessage(channel, "Not logged in (!login)");
                        }
                    }
                    break;
                case "mx":
                case "movex":
                    break;
                case "my":
                case "movey":
                    break;
                case "c":
                case "click":
                    if (enableChat || sender.equalsIgnoreCase(Config.Channel)) {
                        if (checkLogin()) {
                            leftClick();
                        } else {
                            sendTwitchMessage(channel, "Not logged in (!login)");
                        }
                    }
                    break;
                case "rc":
                case "rightclick":
                    if (enableChat || sender.equalsIgnoreCase(Config.Channel)) {
                        if (checkLogin()) {
                            rightClick();
                        } else {
                            sendTwitchMessage(channel, "Not logged in (!login)");
                        }
                    }
                    break;
                case "dr":
                case "drop":
                    if (enableChat || sender.equalsIgnoreCase(Config.Channel)) {
                        if (checkLogin()) {
                            drop();
                        } else {
                            sendTwitchMessage(channel, "Not logged in (!login)");
                        }
                    }
                    break;
                case "d":
                case "drag":
                    if (enableChat || sender.equalsIgnoreCase(Config.Channel)) {
                        if (checkLogin()) {
                            drag();
                        } else {
                            sendTwitchMessage(channel, "Not logged in (!login)");
                        }
                    }
                    break;
                case "login":
                case "l":
                    if (enableChat || sender.equalsIgnoreCase(Config.Channel)) {
                        if (!checkLogin()) {
                            login();
                        }
                    }
                    break;
                case "reset":
                    if (enableChat || sender.equalsIgnoreCase(Config.Channel)) {
                        if (!checkLogin()) {
                            reset();
                        }
                    }
                    break;
                case "camera":
                    if (enableChat || sender.equalsIgnoreCase(Config.Channel)) {
                        if (msg_array.length == 2) {
                            moveCamera(msg_array[1]);
                        }
                    }
                    break;
                case "space":
                case "s":
                    if (enableChat || sender.equalsIgnoreCase(Config.Channel)) {
                        space();
                    }
                    break;
                case "p":
                    if (enableChat || sender.equalsIgnoreCase(Config.Channel)) {
                        if (msg_array.length == 2) {
                            preset(msg_array[1]);
                        } else if (msg_array.length > 2) {
                            String p = "";
                            for (int i = 2; i < msg_array.length; i++) {
                                p += " " + msg_array[i];
                                preset(p);
                            }
                        }
                    }
                    break;
            }

            timeEnd = System.nanoTime();
            time = (float) (timeEnd - timeStart) / 1000000.0f;

            setCmdTime(getCmdTime() * 0.1f + time * 0.9f);
        }
    }

    private void preset(String p) {
        switch (p.toLowerCase()) {
            //Inventory
            case "1":
                break;
            case "2":
                break;
            case "3":
                break;
            case "4":
                break;
            case "5":
                break;
            case "6":
                break;
            case "7":
                break;
            case "8":
                break;
            case "9":
                break;
            case "10":
                break;
            case "11":
                break;
            case "12":
                break;
            case "13":
                break;
            case "14":
                break;
            case "15":
                break;
            case "16":
                break;
            case "17":
                break;
            case "18":
                break;
            case "19":
                break;
            case "20":
                break;
            case "21":
                break;
            case "22":
                break;
            case "23":
                break;
            case "24":
                break;
            case "25":
                break;
            case "26":
                break;
            case "27":
                break;
            case "28":
                break;


            //UI
            case "pray":
                break;
            case "inventory":
                break;
            case "mage":
                break;
            case "gear":
                break;
            case "quest":
                break;
            case "combat":
                break;
            case "emote":
                break;
            case "stats":
                break;
            case "run":
                break;
            case "qp":
                break;

            //Mage
            case "wind strike":
                break;
            case "water strike":
                break;
            case "earth strike":
                break;
            case "fire strike":
                break;
            case "wind bolt":
                break;
            case "water bolt":
                break;
            case "earth bolt":
                break;
            case "fire bolt":
                break;
            case "crumble undead":
                break;
            case "wind blast":
                break;
            case "water blast":
                break;
            case "iban blast":
                break;
            case "magic dart":
                break;
            case "earth blast":
                break;
            case "fire blast":
                break;
            case "saradomin strike":
                break;
            case "claws of guthix":
                break;
            case "flames of zamorak":
                break;
            case "wind wave":
                break;
            case "water wave":
                break;
            case "earth wave":
                break;
            case "fire wave":
                break;
            case "wind surge":
                break;
            case "water surge":
                break;
            case "earth surge":
                break;
            case "fire surge":
                break;
            case "confuse":
                break;
            case "weaken":
                break;
            case "curse":
                break;
            case "bind":
                break;
            case "snare":
                break;
            case "vulnerability":
                break;
            case "enfeeble":
                break;
            case "entangle":
                break;
            case "charge":
                break;
            case "stun":
                break;
            case "bones to bananas":
                break;
            case "low level alchemy":
                break;
            case "superheat item":
                break;
            case "high level alchemy":
                break;
            case "bones to peaches":
                break;
            case "enchant crossbow bolt":
                break;
            case "lvl-1 enchant":
                break;
            case "lvl-2 enchant":
                break;
            case "lvl-3 enchant":
                break;
            case "lvl-4 enchant":
                break;
            case "charge water orb":
                break;
            case "charge earth orb":
                break;
            case "charge fire orb":
                break;
            case "charge air orb":
                break;
            case "lvl-5 enchant":
                break;
            case "lvl-6 enchant":
                break;
            case "lvl-7 enchant":
                break;
            case "home teleport":
                break;
            case "varrock teleport":
                break;
            case "lumbridge teleport":
                break;
            case "falador teleport":
                break;
            case "teleport to house":
                break;
            case "camelot teleport":
                break;
            case "ardougne teleport":
                break;
            case "watchtower teleport":
                break;
            case "trollheim teleport":
                break;
            case "teleport to ape atoll":
                break;
            case "teleport to kourend":
                break;
            case "teleother lumbridge":
                break;
            case "teleother falador":
                break;
            case "tele block":
                break;
            case "teleport to bounty target":
                break;
            case "teleother camelot":
                break;


            //Prayer
            case "thick skin":
                break;
            case "burst of strength":
                break;
            case "clarity of thought":
                break;
            case "sharp eye":
                break;
            case "mystic will":
                break;
            case "rock skin":
                break;
            case "superhuman strength":
                break;
            case "improved reflexes":
                break;
            case "rapid restore":
                break;
            case "rapid heal":
                break;
            case "protect item":
                break;
            case "hawk eye":
                break;
            case "mystic lore":
                break;
            case "steel skin":
                break;
            case "ultimate strength":
                break;
            case "incredible reflexes":
                break;
            case "protect from magic":
                break;
            case "protect from missiles":
                break;
            case "protect from melee":
                break;
            case "eagle eye":
                break;
            case "mystic might":
                break;
            case "retribution":
                break;
            case "redemption":
                break;
            case "smite":
                break;
            case "preserve":
                break;
            case "chivalry":
                break;
            case "piety":
                break;
            case "rigour":
                break;
            case "augury":
                break;


            //Gear
            case "head":
                break;
            case "back":
                break;
            case "neck":
                break;
            case "ring":
                break;
            case "legs":
                break;
            case "body":
                break;
            case "weapon":
                break;
            case "shield":
                break;

                //Combat
            case "style1":
                break;
            case "style2":
                break;
            case "style3":
                break;
            case "style4":
                break;

            case "staff1":
                break;
            case "staff2":
                break;
            case "staff3":
                break;
            case "staff spell":
                break;


        }
    }

    private void movex(int x) {

    }

    private void movey(int y) {

    }

    private void space() {
        Robot robot = null;
        try {
            robot = new Robot();
            robot.keyPress(KeyEvent.VK_SPACE);
            robot.delay(500); // Click one second
            robot.keyRelease(KeyEvent.VK_SPACE);
        } catch (AWTException e) {
            e.printStackTrace();
        }
    }

    private void reset() {
        getDimensions();
        if (!checkLogin()) {
            Robot bot = null;
            try {
                bot = new Robot();
                move(bot, 60, 480);
                bot.mousePress(InputEvent.BUTTON1_MASK);
                bot.mouseRelease(InputEvent.BUTTON1_MASK);
                bot.delay(1000);
                move(bot, 170, 230);
                bot.delay(200);
                bot.mousePress(InputEvent.BUTTON1_MASK);
                bot.mouseRelease(InputEvent.BUTTON1_MASK);
            } catch (AWTException e) {
                e.printStackTrace();
            }
        }
    }

    private void moveCamera(String a) {
        Robot robot = null;
        try {
            robot = new Robot();
        } catch (AWTException e) {
            e.printStackTrace();
            return;
        }

        Point p = MouseInfo.getPointerInfo().getLocation();
        if (!checkMouse(p.x, p.y)) {
            return;
        }

        switch (a) {
            case "up":
                robot.keyPress(KeyEvent.VK_UP);
                robot.delay(500); // Click one second
                robot.keyRelease(KeyEvent.VK_UP);
                break;
            case "down":
                robot.keyPress(KeyEvent.VK_DOWN);
                robot.delay(500); // Click one second
                robot.keyRelease(KeyEvent.VK_DOWN);
                break;
            case "left":
                robot.keyPress(KeyEvent.VK_LEFT);
                robot.delay(500); // Click one second
                robot.keyRelease(KeyEvent.VK_LEFT);
                break;
            case "right":
                robot.keyPress(KeyEvent.VK_RIGHT);
                robot.delay(500); // Click one second
                robot.keyRelease(KeyEvent.VK_RIGHT);
                break;
            case "out":
                robot.mouseWheel(10);
                break;
            case "in":
                robot.mouseWheel(-10);
                break;
        }
    }

    private void type(Robot bot, String s) {
        Point p = MouseInfo.getPointerInfo().getLocation();
        if (bot == null) {
            try {
                bot = new Robot();
            } catch (AWTException e) {
                e.printStackTrace();
            }
        }
        if (!checkMouse(p.x, p.y)) {
            return;
        }

        type2(bot, s);

    }

    private void sendKeys(Robot robot, String keys) {
        for (char c : keys.toCharArray()) {
            System.out.println(c);
            int keyCode = KeyEvent.getExtendedKeyCodeForChar(c);
            if (KeyEvent.CHAR_UNDEFINED == keyCode) {
                throw new RuntimeException(
                        "Key code not found for character '" + c + "'");
            }
            robot.keyPress(keyCode);
            robot.delay(100);
            robot.keyRelease(keyCode);
            robot.delay(100);
        }
    }

    public void type2(Robot bot, CharSequence cs) {
        for (int i = 0; i < cs.length(); i++) {
            type3(bot, cs.charAt(i));
        }
    }

    public void type3(Robot bot, char c) {
        bot.keyPress(KeyEvent.VK_ALT);
        bot.keyPress(KeyEvent.VK_NUMPAD0);
        bot.keyRelease(KeyEvent.VK_NUMPAD0);
        String altCode = Integer.toString(c);
        for (int i = 0; i < altCode.length(); i++) {
            c = (char) (altCode.charAt(i) + '0');
            bot.delay(20);//may be needed for certain applications
            bot.keyPress(c);
            bot.delay(20);//uncomment if necessary
            bot.keyRelease(c);
        }
        bot.keyRelease(KeyEvent.VK_ALT);
    }

    private void move(Robot bot, int x, int y) {
        if (checkBounds(x, y)) {
            if (bot == null) {
                try {
                    bot = new Robot();
                } catch (AWTException e) {
                    e.printStackTrace();
                }
            }
            if (x < 10) {
                x = 10;
            } else {
                x = x + 10;
            }
            if (y < 25) {
                y = 25;
            } else {
                y = y + 20;
            }
            bot.mouseMove(x + bounds[0], y + bounds[1]);

        }
    }

    private void leftClick() {
        Robot bot = null;
        Point p = MouseInfo.getPointerInfo().getLocation();
        if (!checkMouse(p.x, p.y)) {
            return;
        }

        try {
            bot = new Robot();
            bot.mousePress(InputEvent.BUTTON1_MASK);
            bot.mouseRelease(InputEvent.BUTTON1_MASK);
        } catch (AWTException e) {
            e.printStackTrace();
        }
    }

    private void rightClick() {
        Robot bot = null;
        Point p = MouseInfo.getPointerInfo().getLocation();
        logMsg(p.toString());
        if (!checkMouse(p.x, p.y)) {
            return;
        }
        try {
            bot = new Robot();
            bot.mousePress(InputEvent.BUTTON3_MASK);
            bot.mouseRelease(InputEvent.BUTTON3_MASK);
        } catch (AWTException e) {
            e.printStackTrace();
        }
    }

    private void drag() {
        Robot bot = null;
        Point p = MouseInfo.getPointerInfo().getLocation();
        if (!checkMouse(p.x, p.y)) {
            return;
        }
        try {
            bot = new Robot();
            bot.mousePress(InputEvent.BUTTON1_MASK);
        } catch (AWTException e) {
            e.printStackTrace();
        }
    }

    private void drop() {
        Robot bot = null;
        Point p = MouseInfo.getPointerInfo().getLocation();
        if (!checkMouse(p.x, p.y)) {
            return;
        }
        try {
            bot = new Robot();
            bot.mouseRelease(InputEvent.BUTTON1_MASK);
        } catch (AWTException e) {
            e.printStackTrace();
        }
    }

    private void login() {
        if (!logging) {
            logging = true;
            getDimensions();
            if (!checkLogin()) {
                Robot bot = null;
                try {
                    bot = new Robot();
                    move(bot, 100, 100);
                    bot.mousePress(InputEvent.BUTTON1_MASK);
                    bot.mouseRelease(InputEvent.BUTTON1_MASK);
                    bot.keyPress(KeyEvent.VK_ENTER);
                    bot.keyRelease(KeyEvent.VK_ENTER);
                    type(bot, Config.Username);
                    bot.keyPress(KeyEvent.VK_ENTER);
                    bot.keyRelease(KeyEvent.VK_ENTER);
                    type(bot, Config.Password);
                    bot.keyPress(KeyEvent.VK_ENTER);
                    bot.keyRelease(KeyEvent.VK_ENTER);
                    move(bot, 460, 320);
                } catch (AWTException e) {
                    e.printStackTrace();
                }
            }

            logging = false;
        }
    }

    private static boolean checkLogin() {
        BufferedReader br = null;
        String s = "";
        bounds = getDimensions();

        try {
            br = new BufferedReader(new FileReader("C:/logged.txt"));
            s = br.readLine();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (s.equals("online") && bounds != null) {
            return true;
        }
        return false;
    }

    public static boolean checkMouse(int x, int y) {
        if (x >= bounds[2] - 10) {
            logMsg("OOB MX");
            return false;
        }
        if (y >= bounds[3] - 10) {
            logMsg("OOB MY");
            return false;
        }
        logMsg("OOB MPASS");
        return true;
    }

    public static boolean checkBounds(int x, int y) {
        if ((bounds[0] + x) >= (bounds[2] - 40)) {
            logMsg("OOB X");
            return false;
        }
        if ((bounds[1] + y) >= (bounds[3] - 20)) {
            logMsg("OOB Y");
            return false;
        }
        logMsg("OOB PASS");
        return true;
    }

    public interface User32 extends StdCallLibrary {
        User32 INSTANCE = (User32) Native.loadLibrary("user32", User32.class,
                W32APIOptions.DEFAULT_OPTIONS);

        HWND FindWindow(String lpClassName, String lpWindowName);

        boolean EnumWindows(WinUser.WNDENUMPROC lpEnumFunc, Pointer arg);

        WinDef.HWND SetFocus(WinDef.HWND hWnd);

        int GetWindowTextA(HWND hWnd, byte[] lpString, int nMaxCount);

        boolean SetForegroundWindow(WinDef.HWND hWnd);

        int GetWindowRect(HWND handle, int[] rect);
    }

    public static int[] getRect(String windowName) throws WindowNotFoundException,
            GetWindowRectException {
        HWND hwnd = User32.INSTANCE.FindWindow(null, windowName);
        if (hwnd == null) {
            throw new WindowNotFoundException("", windowName);
        }

        int[] rect = {0, 0, 0, 0};
        int result = User32.INSTANCE.GetWindowRect(hwnd, rect);
        if (result == 0) {
            throw new GetWindowRectException(windowName);
        }
        return rect;
    }

    public static int[] getDimensions() {
        String w = Config.App;
        int[] rect = null;

        try {
            rect = getRect(w);
            System.out.printf("The corner locations for the window \"%s\" are %s",
                    w, Arrays.toString(rect));


        } catch (WindowNotFoundException e) {
            e.printStackTrace();
        } catch (GetWindowRectException e) {
            e.printStackTrace();
        }
        bounds = rect;
        return rect;
    }

    @SuppressWarnings("serial")
    public static class WindowNotFoundException extends Exception {
        public WindowNotFoundException(String className, String windowName) {
            super(String.format("Window null for className: %s; windowName: %s",
                    className, windowName));
        }
    }

    @SuppressWarnings("serial")
    public static class GetWindowRectException extends Exception {
        public GetWindowRectException(String windowName) {
            super("Window Rect not found for " + windowName);
        }
    }

    @Override
    public void onPrivateMessage(String sender, String login, String hostname, String message) {
        logMsg("data", "/privmsg", "User: " + sender + " Hostname: " + hostname + " Message: " + message);
    }

    public ArrayList<TwitchChannel> getTwitchChannels() {
        return m_channels;
    }

    public TwitchChannel getTwitchChannel(String name) {
        TwitchChannel result = null;

        for (TwitchChannel tc : m_channels) {
            if (tc.getName().equals(name)) {
                result = tc;
                break;
            }
        }

        return result;
    }

    public ArrayList<TwitchUser> getAllUsers() {
        ArrayList<TwitchUser> result = new ArrayList<TwitchUser>();

        for (TwitchChannel tc : m_channels) {
            result.addAll(tc.getUsers());
        }

        return result;
    }

    public ArrayList<TwitchUser> getAllOperators() {
        ArrayList<TwitchUser> result = new ArrayList<TwitchUser>();

        for (TwitchChannel tc : m_channels) {
            result.addAll(tc.getOperators());
        }

        return result;
    }

    public ArrayList<TwitchUser> getOnlineModerators() {
        ArrayList<TwitchUser> result = new ArrayList<TwitchUser>();

        for (TwitchChannel tc : m_channels) {
            result.addAll(tc.getModerators());
        }

        return result;
    }

    public ArrayList<TwitchUser> getOfflineModerators() {
        return m_moderators;
    }

    public TwitchUser getOfflineModerator(String nick) {
        TwitchUser result = null;

        for (TwitchUser tu : m_moderators) {
            if (tu.getName().equals(nick)) {
                result = tu;
            }
        }

        return result;
    }

    public float getCycleTime() {
        return m_cycleTime;
    }

    public void setCycleTime(float cycleTime) {
        m_cycleTime = cycleTime;
    }

    public float getCmdTime() {
        return m_cmdTime;
    }

    public void setCmdTime(float cmdTime) {
        m_cmdTime = cmdTime;
    }

    public boolean isInitialized() {
        return m_hasMembership & m_hasCommands & m_hasTags;
    }

}
