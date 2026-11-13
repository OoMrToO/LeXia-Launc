package tv.lexia.launcher;

interface IAddonNetworkBroker {
    String fetch(String repository, String providerId, String requestJson);
}