function requireMethod(port, portName, method) {
  if (!port || typeof port[method] !== 'function') {
    throw new TypeError(`${portName}.${method} is required`)
  }
}

export function assertGatewayPorts({ sessions, provider, clock }) {
  requireMethod(sessions, 'sessions', 'transact')
  for (const method of ['openTurn', 'pushAudio', 'finishTurn', 'cancelTurn', 'reconcileTurn']) {
    requireMethod(provider, 'provider', method)
  }
  requireMethod(clock, 'clock', 'now')
}

export function assertWebSocketPorts({ authorizer, gateway, clock }) {
  requireMethod(authorizer, 'authorizer', 'authenticate')
  for (const method of [
    'connect',
    'disconnect',
    'startTurn',
    'pushAudio',
    'finishTurn',
    'cancelTurn',
    'turnStatus',
    'reconcileTurn',
  ]) {
    requireMethod(gateway, 'gateway', method)
  }
  requireMethod(clock, 'clock', 'now')
}
