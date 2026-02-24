import 'package:lin_player_core/app_config/app_config.dart';
import 'package:lin_player_core/app_config/app_product.dart';
import 'package:lin_player_core/state/media_server_type.dart';
import 'package:lin_player_server_adapters/lin_player_server_adapters.dart';

import 'uhd/uhd_emby_like_adapter.dart';

class UhdAwareServerAdapterFactory {
  static MediaServerAdapter forLogin({
    required MediaServerType serverType,
    required String deviceId,
  }) {
    if (AppConfig.current.product == AppProduct.uhd && serverType.isEmbyLike) {
      return UhdEmbyLikeAdapter(serverType: serverType, deviceId: deviceId);
    }
    return ServerAdapterFactory.forLogin(serverType: serverType, deviceId: deviceId);
  }
}

