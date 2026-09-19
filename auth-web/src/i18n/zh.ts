export default {
  brand: { name: 'Auth Server', slogan: '安全登录，\n从这里开始。' },
  login: {
    title: '登录',
    subtitle: '使用你的组织账号继续',
    account: '账号',
    password: '密码',
    submit: '登录',
    submitting: '登录中…',
    invalid: '账号或密码不正确。',
    failed: '登录失败，请稍后重试。',
  },
  organizations: {
    title: '选择组织',
    subtitle: '你属于多个组织，选择一个继续',
    submit: '继续',
    denied: '当前账号没有可用的组织，请联系管理员。',
    failed: '加载组织失败，请刷新重试。',
  },
  consent: {
    title: '授权确认',
    subtitle: '{client} 请求访问你的账号',
    asOrganization: '以 {organization} 身份授权',
    approve: '同意授权',
    failed: '加载授权信息失败，请回到客户端重新发起。',
  },
  home: {
    title: '已登录',
    subtitle: '当前没有待处理的授权请求',
    account: '账号',
    organization: '组织',
    logout: '退出登录',
  },
  scope: {
    profile: '读取基本资料',
    weatherRead: '读取天气数据',
  },
}
