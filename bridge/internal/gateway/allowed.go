package gateway

var allowedRPC = map[string]bool{
	"session/list": true, "session/create": true, "session/page": true, "session/modelCatalog": true, "session/projections": true, "session/selectModel": true, "session/rename": true, "session/fork": true, "session/prompt": true, "session/cancel": true,
	"workspace/create": true, "directoryPicker/list": true, "directoryPicker/createDirectory": true, "agentPresets/list": true, "agentPresets/select": true, "llm/listProviders": true, "llm/listConfigurableProviders": true, "settings/describe": true, "credentials/describe": true, "credentials/set": true, "commands/execute": true, "messageFeedback/list": true, "messageFeedback/put": true, "messageFeedback/delete": true, "$events/result": true,
}
var allowedStream = map[string]bool{"session/follow": true, "session/control": true, "workspace/follow": true, "$events": true}
