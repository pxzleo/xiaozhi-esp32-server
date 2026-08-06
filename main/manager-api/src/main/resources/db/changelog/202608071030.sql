INSERT INTO ai_model_provider (
    id,
    model_type,
    provider_code,
    name,
    fields,
    sort,
    creator,
    create_date,
    updater,
    update_date
)
VALUES (
    'SYSTEM_PLUGIN_NETEASE_MUSIC',
    'Plugin',
    'play_netease_music',
    '网易云音乐播放',
    JSON_ARRAY(
        JSON_OBJECT(
            'key', 'api_base_url',
            'type', 'string',
            'label', '网易云音乐 API 地址',
            'default', 'http://127.0.0.1:3000'
        ),
        JSON_OBJECT(
            'key', 'cookie',
            'type', 'secret',
            'label', '网易云音乐登录凭据',
            'default', ''
        ),
        JSON_OBJECT(
            'key', 'quality',
            'type', 'string',
            'label', '音质 standard/higher/exhigh/lossless/hires',
            'default', 'standard'
        ),
        JSON_OBJECT(
            'key', 'max_tracks',
            'type', 'number',
            'label', '歌单单次最多播放歌曲数',
            'default', 20
        ),
        JSON_OBJECT(
            'key', 'prepare_timeout_seconds',
            'type', 'number',
            'label', '播放请求总准备超时（秒，最大25）',
            'default', 20
        ),
        JSON_OBJECT(
            'key', 'cache_size_mb',
            'type', 'number',
            'label', '音乐缓存上限（MB）',
            'default', 512
        ),
        JSON_OBJECT(
            'key', 'cache_ttl_hours',
            'type', 'number',
            'label', '音乐缓存有效期（小时）',
            'default', 24
        )
    ),
    25,
    0,
    NOW(),
    0,
    NOW()
);
