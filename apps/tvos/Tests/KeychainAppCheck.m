#import <UIKit/UIKit.h>

int lp_keychain_check(const char *name, const char *phase);

@interface KeychainScene : UIResponder <UIWindowSceneDelegate>
@property(nonatomic, strong) UIWindow *window;
@end

@implementation KeychainScene
- (void)scene:(UIScene *)scene willConnectToSession:(UISceneSession *)session options:(UISceneConnectionOptions *)options {
    self.window = [[UIWindow alloc] initWithWindowScene:(UIWindowScene *)scene];
    self.window.rootViewController = [UIViewController new];
    [self.window makeKeyAndVisible];
    dispatch_async(dispatch_get_main_queue(), ^{
        NSDictionary *env = NSProcessInfo.processInfo.environment;
        NSString *name = env[@"LP_CHECK_NAME"], *phase = env[@"LP_CHECK_PHASE"];
        NSCAssert(name.length > 0 && phase.length > 0, @"缺少测试参数");
        NSFileManager *files = NSFileManager.defaultManager;
        NSURL *documents = [files URLsForDirectory:NSDocumentDirectory inDomains:NSUserDomainMask].firstObject;
        freopen([[documents URLByAppendingPathComponent:@"stderr"] path].fileSystemRepresentation, "w", stderr);
        NSURL *cache = [[files URLsForDirectory:NSCachesDirectory inDomains:NSUserDomainMask].firstObject URLByAppendingPathComponent:@"KeychainFixture"];
        if ([phase isEqualToString:@"save"]) {
            NSCAssert([files createDirectoryAtURL:cache.URLByDeletingLastPathComponent withIntermediateDirectories:YES attributes:nil error:nil], @"缓存目录创建失败");
            NSCAssert([[@"fixture" dataUsingEncoding:NSUTF8StringEncoding] writeToURL:cache atomically:YES], @"缓存夹具写入失败");
        } else if ([phase isEqualToString:@"verify"]) {
            NSCAssert(![files fileExistsAtPath:cache.path], @"未清理缓存夹具");
        }
        NSCAssert(lp_keychain_check(name.UTF8String, phase.UTF8String) == 0, @"安全存储检查失败");
        NSURL *done = [documents URLByAppendingPathComponent:[phase stringByAppendingString:@".done"]];
        NSCAssert([[@"ok" dataUsingEncoding:NSUTF8StringEncoding] writeToURL:done atomically:YES], @"检查结果写入失败");
    });
}
@end

@interface KeychainDelegate : UIResponder <UIApplicationDelegate>
@end
@implementation KeychainDelegate
- (UISceneConfiguration *)application:(UIApplication *)application configurationForConnectingSceneSession:(UISceneSession *)session options:(UISceneConnectionOptions *)options {
    UISceneConfiguration *config = [[UISceneConfiguration alloc] initWithName:@"KeychainCheck" sessionRole:session.role];
    config.delegateClass = KeychainScene.class;
    return config;
}
@end

int main(int argc, char **argv) {
    @autoreleasepool {
        return UIApplicationMain(argc, argv, nil, NSStringFromClass(KeychainDelegate.class));
    }
}
